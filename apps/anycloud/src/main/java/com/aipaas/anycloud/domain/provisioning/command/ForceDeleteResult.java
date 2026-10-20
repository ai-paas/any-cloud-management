package com.aipaas.anycloud.domain.provisioning.command;

import java.util.List;

/**
 * 강제 삭제 결과.
 *
 * @param removedRecords 삭제한 기록 수. 동일 이름 세대가 여럿이면 그 개수
 * @param orphanedStacks 클라우드에 잔존 가능한 스택. 비어 있으면 생성된 자원 없음
 */
public record ForceDeleteResult(int removedRecords, List<String> orphanedStacks) {}
