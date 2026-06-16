package com.aipaas.anycloud.common.util;

import java.util.regex.Pattern;

public final class UuidValidator {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private UuidValidator() {}

    public static boolean isValid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }
}
