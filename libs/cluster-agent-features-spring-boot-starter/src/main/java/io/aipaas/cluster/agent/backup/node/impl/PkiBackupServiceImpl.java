package io.aipaas.cluster.agent.backup.node.impl;

import io.aipaas.cluster.agent.v1.CommandType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Struct;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.backup.node.BackupResult;
import io.aipaas.cluster.agent.backup.node.PkiBackupService;
import io.aipaas.cluster.agent.backup.core.BackupException;
import java.time.Duration;

public class PkiBackupServiceImpl implements PkiBackupService {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final BackupDispatchSupport dispatch;

	public PkiBackupServiceImpl(AgentSessionRegistry sessionRegistry, Duration timeout) {
		this.dispatch = new BackupDispatchSupport(sessionRegistry, timeout);
	}

	@Override
	public BackupResult backup(String clusterName, PkiBackupOptions options) {
		String includePathsJson;
		try {
			includePathsJson = options.includePaths() == null || options.includePaths().isEmpty()
					? ""
					: MAPPER.writeValueAsString(options.includePaths());
		} catch (JsonProcessingException e) {
			throw new BackupException("INVALID_PARAMS",
					"failed to serialize include_paths: " + e.getMessage(), e);
		}
		Struct params = Struct.newBuilder()
				.putFields("include_paths", BackupDispatchSupport.strVal(includePathsJson))
				.putFields("chunk_size", BackupDispatchSupport.strVal(
						options.chunkSize() <= 0 ? "" : Integer.toString(options.chunkSize())))
				.build();
		return dispatch.dispatch(clusterName, CommandType.BACKUP_PKI, params);
	}
}
