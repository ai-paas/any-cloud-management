package io.aipaas.cluster.provisioning.api.exception;

import io.aipaas.cluster.provisioning.api.ProvisioningResult;
import jakarta.validation.ConstraintViolation;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * Pulumi raw output 이 {@link ProvisioningResult} schema 와 일치하지 않을 때 던진다.
 *
 * <p>새 provider 추가 시 표준 키를 누락하거나, 기존 provider 가 회귀로 빈 값을 export 하는 경우 운영 시점에
 * 즉시 인지하기 위함.
 *
 * <p>host backend 가 audit log 에 FAILED 로 기록.
 */
@Getter
public class ProvisioningResultValidationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final Set<ConstraintViolation<ProvisioningResult>> violations;

	public ProvisioningResultValidationException(Set<ConstraintViolation<ProvisioningResult>> violations) {
		super("ProvisioningResult schema validation failed: " + summary(violations));
		this.violations = violations;
	}

	private static String summary(Set<ConstraintViolation<ProvisioningResult>> violations) {
		return violations.stream()
				.map(v -> v.getPropertyPath() + " " + v.getMessage())
				.collect(Collectors.joining("; "));
	}
}
