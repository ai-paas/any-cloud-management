package com.aipaas.anycloud.domain.cluster;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ClusterEntity#getTags()} 를 JSON 문자열로 저장.
 *
 * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} 은 쓰지 않습니다. MariaDB 의 JSON 은 네이티브 타입이
 * 아니라 {@code LONGTEXT CHECK (json_valid(..))} 의 별칭이라 information_schema 가 longtext 로
 * 보고하는데, JdbcTypeCode 는 {@code Types#JSON} 을 기대해 ddl-auto=validate 에서 부팅이
 * 실패합니다. MySQL 에서는 통과하므로 dialect 를 바꾸면 재현되지 않습니다.
 *
 * <p>컬럼 타입은 그대로 JSON 입니다. 이 converter 가 쓰는 값도 JSON 이라 json_valid CHECK 를
 * 만족합니다.
 *
 * <p>autoApply 는 켜지 마세요. 다른 Map 필드까지 끌어가 의도하지 않은 직렬화가 생깁니다.
 */
@Converter
public class ClusterTagsConverter implements AttributeConverter<Map<String, String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> TYPE = new TypeReference<>() {};
    private static final String EMPTY = "{}";

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return EMPTY;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("cluster.tags 직렬화 실패: " + attribute.keySet(), e);
        }
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 손상된 행 하나가 cluster 목록 조회 전체를 막지 않도록 빈 Map 으로 떨어뜨린다.
            throw new IllegalStateException("cluster.tags 역직렬화 실패", e);
        }
    }
}
