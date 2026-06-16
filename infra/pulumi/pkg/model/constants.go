package model

// L8: 모든 provider 가 공유하는 port / 네트워크 상수. provider 별 파일에서 magic number 사용 금지.
const (
	// PortKubernetesAPIServer is the kube-apiserver port (kubeadm default).
	PortKubernetesAPIServer = 6443
	// PortSSH is the standard SSH port. Pulumi 가 worker bootstrap 에 사용.
	PortSSH = 22
	// NodePortRangeMin / NodePortRangeMax — K8s service NodePort 기본 범위.
	NodePortRangeMin = 30000
	NodePortRangeMax = 32767
	// PortEtcdClient — control-plane HA 시 외부 etcd 접근.
	PortEtcdClient = 2379
	// PortEtcdPeer — etcd peer-to-peer.
	PortEtcdPeer = 2380
	// PortKubeletAPI — kubelet metric / log.
	PortKubeletAPI = 10250
)
