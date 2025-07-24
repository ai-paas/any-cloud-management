package com.aipaas.anycloud.error.exception;

import com.aipaas.anycloud.error.enums.ErrorCode;

import java.util.List;

public class EntityNotFoundException extends CustomException {

    public EntityNotFoundException(String message) {
        super(message, ErrorCode.ENTITY_NOT_FOUND);
    }

    public static <T> List<T> requireNotEmpty(List<T> items, String message) throws EntityNotFoundException {
        if (items.isEmpty()) {
            throw new EntityNotFoundException(message);
        }
        return items;
    }
}