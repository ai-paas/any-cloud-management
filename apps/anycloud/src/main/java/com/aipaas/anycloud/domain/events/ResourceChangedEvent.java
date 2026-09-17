package com.aipaas.anycloud.domain.events;

/**
 * 무언가 바뀌었다는 신호. 바뀐 값은 담지 않는다.
 *
 * <p>화면은 이 신호를 받아 해당 쿼리를 다시 부른다. 값을 스트림으로 실어 나르면 REST 응답과
 * 스트림이 서로 다른 답을 하게 되고, 어느 쪽이 맞는지 아무도 모르게 된다.
 *
 * @param type 'vmCluster', 'operation', 'credential', 'cluster'
 * @param name 바뀐 자원의 이름. 전체가 바뀌었으면 null
 */
public record ResourceChangedEvent(String type, String name) {}
