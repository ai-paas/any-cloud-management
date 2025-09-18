package com.aipaas.anycloud.error.exception;

import com.aipaas.anycloud.error.enums.ErrorCode;
import lombok.Getter;

/**
 * 클러스터를 찾을 수 없을 때 발생하는 예외
 */
@Getter
public class ClusterNotFoundException extends RuntimeException {

  private final ErrorCode errorCode;
  private final String clusterName;

  public ClusterNotFoundException(String clusterName) {
    super("Cluster with name '" + clusterName + "' not found");
    this.errorCode = ErrorCode.CLUSTER_NOT_FOUND;
    this.clusterName = clusterName;
  }

  public ClusterNotFoundException(String clusterName, String message) {
    super(message);
    this.errorCode = ErrorCode.CLUSTER_NOT_FOUND;
    this.clusterName = clusterName;
  }
}
