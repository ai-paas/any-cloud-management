package com.aipaas.anycloud.util;

import java.util.UUID;

public class UuidGenerator {
    public static UUID next() {
        return UUID.randomUUID();
    }

}