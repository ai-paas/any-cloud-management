package com.aipaas.anycloud.domain.provisioning.command;

import java.util.List;

/**
 * 강제 삭제 결과.
 *
 * @param removedRecords 지운 기록 수. 같은 이름의 세대가 여럿이면 그만큼이다
 * @param orphanedStacks 클라우드에 남아 있을 수 있는 스택. 비어 있으면 만들어진 자원이 없었다
 */
public record ForceDeleteResult(int removedRecords, List<String> orphanedStacks) {}
