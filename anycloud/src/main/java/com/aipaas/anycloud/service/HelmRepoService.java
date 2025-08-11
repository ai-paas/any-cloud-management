package com.aipaas.anycloud.service;

import com.aipaas.anycloud.model.dto.request.CreateHelmRepoDto;
import com.aipaas.anycloud.model.entity.HelmRepoEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <pre>
 * ClassName : HelmRepoService
 * Type : interface
 * Description : 헬름저장소 관련 함수를 정리한 인터페이스입니다.
 * Related : HelmRepoController, HelmRepoServiceImpl
 * </pre>
 */
@Component
public interface HelmRepoService {
	List<HelmRepoEntity> getHelmRepos();
	HelmRepoEntity getHelmRepo(String name);
	Boolean isHelmExist(String name);
	HttpStatus createHelmRepo(CreateHelmRepoDto createHelmRepoDto);
	HttpStatus deleteHelmRepo(String name);
}
