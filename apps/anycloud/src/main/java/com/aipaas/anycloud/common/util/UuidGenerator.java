package com.aipaas.anycloud.common.util;

import java.util.UUID;

public class UuidGenerator {
    public static UUID next() {
        return UUID.randomUUID();
    }
}
