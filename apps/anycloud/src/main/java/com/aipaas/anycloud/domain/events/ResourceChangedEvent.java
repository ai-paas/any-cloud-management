package com.aipaas.anycloud.domain.events;

/**
 * 변경 발생 신호. 변경된 값은 미포함.
 *
 * <p>화면은 이 신호 수신 후 해당 쿼리 재호출. 값을 스트림으로 전달하면 REST 응답과 스트림의
 * 결과가 달라지고 기준 판별 불가.
 *
 * @param type 'vmCluster', 'operation', 'credential', 'cluster'
 * @param name 변경된 자원 이름. 전체 변경 시 null
 */
public record ResourceChangedEvent(String type, String name) {}
