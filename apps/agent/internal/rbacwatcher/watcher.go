// Package rbacwatcher — anycloud-managed ClusterRoleBinding 의 변경을 K8s Watch API 로 감지해
// backend 로 AgentEvent 형태로 push. backend 의 BindingFleetView cache invalidation 트리거.
//
// Label selector aipaas.io/managed-by=anycloud 매칭. 다른 owner 가 만든 ClusterRoleBinding
// 은 무시 (운영자가 manual 생성하거나 Argo CD 가 reconcile 한 것 등).
package rbacwatcher

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"google.golang.org/protobuf/types/known/structpb"
	"google.golang.org/protobuf/types/known/timestamppb"
	"k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/watch"
	"k8s.io/client-go/kubernetes"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
)

const (
	// label selector — backend 의 BindingApplyClient 가 부착하는 label 과 매칭.
	labelSelector = "aipaas.io/managed-by=anycloud"

	// resync 주기 — watch 가 끊기거나 missed event 가능성 대비 주기적 full resync.
	resyncInterval = 5 * time.Minute

	eventBindingChanged   = "rbac.binding.changed"
	eventBindingSyncStart = "rbac.binding.sync.start"
	eventBindingSyncEnd   = "rbac.binding.sync.end"
)

// EventSender — runtime stream 의 send 추상화. test 용 interface.
type EventSender interface {
	SendEvent(event *agentv1.AgentEvent) error
}

// Run — K8s ClusterRoleBinding watch loop. stream send error 시 caller 가 stream 재시작.
//
// 호출 패턴: runtime.connectAndStream 의 goroutine 으로 실행. stream cancel 시 context 종료.
func Run(ctx context.Context, kube kubernetes.Interface, sender EventSender) error {
	if kube == nil {
		// K8s client 부재 — informer 미동작. error 가 아닌 noop. caller 가 stream 유지.
		<-ctx.Done()
		return ctx.Err()
	}
	if sender == nil {
		return errors.New("rbacwatcher: EventSender nil")
	}

	// 1. 초기 full resync 알림 — backend cache 가 첫 시점에 stale 가능성 cover.
	if err := sendSyncStart(sender); err != nil {
		slog.Warn("rbacwatcher: send sync.start failed", slog.String("error", err.Error()))
	}

	for {
		if err := watchOnce(ctx, kube, sender); err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				return err
			}
			slog.Warn("rbacwatcher: watch ended with error, retrying",
				slog.String("error", err.Error()))
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(3 * time.Second):
			}
			continue
		}
		// watch closed cleanly — 짧은 backoff 후 재개 (K8s API timeout 등).
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(1 * time.Second):
		}
	}
}

// watchOnce — 단일 watch session. resyncInterval timeout 도달 시 정상 종료 후 caller 가 재호출.
func watchOnce(ctx context.Context, kube kubernetes.Interface, sender EventSender) error {
	timeoutSeconds := int64(resyncInterval.Seconds())
	w, err := kube.RbacV1().ClusterRoleBindings().Watch(ctx, v1.ListOptions{
		LabelSelector:  labelSelector,
		TimeoutSeconds: &timeoutSeconds,
	})
	if err != nil {
		return err
	}
	defer w.Stop()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case ev, ok := <-w.ResultChan():
			if !ok {
				// channel closed — caller 가 재호출.
				return nil
			}
			if ev.Type == watch.Error {
				return errors.New("watch returned error event")
			}
			if err := sendChanged(sender, ev); err != nil {
				slog.Warn("rbacwatcher: send event failed",
					slog.String("error", err.Error()))
				// send 실패는 stream 자체 문제 — 종료해서 caller (runtime) 가 reconnect.
				return err
			}
		}
	}
}

func sendChanged(sender EventSender, ev watch.Event) error {
	meta := extractMeta(ev)
	payload, _ := structpb.NewStruct(map[string]interface{}{
		"action":      string(ev.Type),
		"k8s_kind":    "ClusterRoleBinding",
		"k8s_name":    meta.name,
		"template_id": meta.templateID,
		"oidc_group":  meta.oidcGroup,
	})
	return sender.SendEvent(&agentv1.AgentEvent{
		EventType:  eventBindingChanged,
		OccurredAt: timestamppb.Now(),
		Payload:    payload,
	})
}

func sendSyncStart(sender EventSender) error {
	return sender.SendEvent(&agentv1.AgentEvent{
		EventType:  eventBindingSyncStart,
		OccurredAt: timestamppb.Now(),
	})
}

type bindingMeta struct {
	name       string
	templateID string
	oidcGroup  string
}

func extractMeta(ev watch.Event) bindingMeta {
	out := bindingMeta{}
	if ev.Object == nil {
		return out
	}
	type metaAccessor interface {
		GetName() string
		GetLabels() map[string]string
	}
	if m, ok := ev.Object.(metaAccessor); ok {
		out.name = m.GetName()
		labels := m.GetLabels()
		out.templateID = labels["aipaas.io/template"]
		out.oidcGroup = labels["aipaas.io/oidc-group"]
	}
	return out
}
