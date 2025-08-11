package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.error.enums.ErrorCode;
import com.aipaas.anycloud.error.exception.CustomException;
import com.aipaas.anycloud.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.model.dto.request.CreateHelmRepoDto;
import com.aipaas.anycloud.model.entity.HelmRepoEntity;
import com.aipaas.anycloud.repository.HelmRepoRepository;
import com.aipaas.anycloud.service.HelmRepoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <pre>
 * ClassName : helmRepoServiceImpl
 * Type : class
 * Description : 헬름저장소와 관련된 서비스 구현과 관련된 함수를 포함하고 있는 클래스입니다.
 * Related : HelmRepoController, HelmRepoService
 * </pre>
 */
@Slf4j
@Service("helmRepoServiceImpl")
@Transactional
@RequiredArgsConstructor
public class HelmRepoServiceImpl implements HelmRepoService {

	private final HelmRepoRepository helmRepoRepository;

	/**
	 * [HelmRepoServiceImpl] 헬름저장소 전체 목록 함수
	 *
	 * @return 전체 헬름저장소 목록을 반환합니다.
	 */
	@Transactional(readOnly = true)
	public List<HelmRepoEntity> getHelmRepos() {
		return helmRepoRepository.findAll();
	}

	/**
	 * [HelmRepoServiceImpl] 헬름저장소 단일 조회 함수
	 *
	 * @return 헬름저장소를 조회합니다.
	 */
	@Transactional(readOnly = true)
	public HelmRepoEntity getHelmRepo(String helmRepoName) {
		return helmRepoRepository.findByName(helmRepoName).orElseThrow(
			() -> new EntityNotFoundException("HelmRepo with Name " + helmRepoName + " Not Found."));
	}

	/**
	 * [HelmRepoServiceImpl] 헬름저장소 생성 함수
	 *
	 * @return 헬름저장소를 등록합니다.
	 */
	@Transactional
	public HttpStatus createHelmRepo(CreateHelmRepoDto helmRepo) {

		if (helmRepo == null) {
			throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
		}

		log.error("ttest");

		log.error(isHelmExist(helmRepo.getName()).toString());

		if (isHelmExist(helmRepo.getName())) {
			throw new CustomException(ErrorCode.DUPLICATE);
		}
		HelmRepoEntity helmRepoEntity = HelmRepoEntity.builder()
			.name(helmRepo.getName())
			.url(helmRepo.getUrl())
			.username(helmRepo.getUsername())
			.password(helmRepo.getPassword())
			.caFile(helmRepo.getCaFile())
			.insecureSkipTlsVerify(helmRepo.isInsecureSkipTlsVerify())
			.build();

		try {
			helmRepoRepository.save(helmRepoEntity);
			helmRepoRepository.flush();

		} catch (DataIntegrityViolationException e) {
			log.error(e.getMessage());
			throw new CustomException(ErrorCode.DATA_INTEGRITY);
		}
		return HttpStatus.CREATED;
	}


	/**
	 * [HelmRepoServiceImpl] 헬름저장소 삭제 함수
	 *
	 * @return 헬름저장소를 삭제합니다.
	 */
	public HttpStatus deleteHelmRepo(String helmRepoName) {
		helmRepoRepository.delete(helmRepoRepository.findByName(helmRepoName).orElseThrow(
			() -> new EntityNotFoundException("HelmRepo with Name " + helmRepoName + " Not Found.")));
		return HttpStatus.OK;
	}

	/**
	 * [HelmRepoServiceImpl] 헬름저장소 중복 확인 함수
	 *
	 * @return 헬름저장소를 중복 체크합니다.
	 */
	public Boolean isHelmExist(String helmRepoName) {
		return helmRepoRepository.existsByName(helmRepoName);
		}
}
