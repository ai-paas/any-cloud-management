package io.aipaas.cluster.agent.backup.node.impl;

import io.aipaas.cluster.agent.v1.CommandType;
import com.google.protobuf.Struct;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.backup.node.BackupResult;
import io.aipaas.cluster.agent.backup.node.EtcdBackupService;
import java.time.Duration;

public class EtcdBackupServiceImpl implements EtcdBackupService {

	private final BackupDispatchSupport dispatch;

	public EtcdBackupServiceImpl(AgentSessionRegistry sessionRegistry, Duration timeout) {
		this.dispatch = new BackupDispatchSupport(sessionRegistry, timeout);
	}

	@Override
	public BackupResult backup(String clusterName, EtcdBackupOptions options) {
		Struct params = Struct.newBuilder()
				.putFields("endpoint", BackupDispatchSupport.strVal(options.endpoint()))
				.putFields("ca_cert_path", BackupDispatchSupport.strVal(options.caCertPath()))
				.putFields("client_cert_path", BackupDispatchSupport.strVal(options.clientCertPath()))
				.putFields("client_key_path", BackupDispatchSupport.strVal(options.clientKeyPath()))
				.putFields("chunk_size", BackupDispatchSupport.strVal(
						options.chunkSize() <= 0 ? "" : Integer.toString(options.chunkSize())))
				.build();
		return dispatch.dispatch(clusterName, CommandType.BACKUP_ETCD, params);
	}
}
