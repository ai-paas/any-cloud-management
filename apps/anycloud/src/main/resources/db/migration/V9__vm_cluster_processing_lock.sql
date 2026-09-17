-- 처리 중 소유권. last_processed_workflow_message_id 는 처리가 끝난 뒤 기록되므로
-- 처리 도중 도착한 재전달 메시지를 막지 못한다. AMQP delivery ack 타임아웃에 걸려
-- 메시지가 재전달되면서 같은 노드에 부트스트랩이 중복으로 붙는 사고가 있었다.
ALTER TABLE vm_cluster
    ADD COLUMN processing_message_id VARCHAR(36) NULL,
    ADD COLUMN processing_started_at TIMESTAMP NULL;

-- 오래된 소유권을 회수할 때 스캔한다.
CREATE INDEX idx_vm_cluster_processing ON vm_cluster (processing_started_at);
