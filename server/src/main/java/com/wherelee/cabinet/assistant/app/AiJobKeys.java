package com.wherelee.cabinet.assistant.app;

import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

public final class AiJobKeys {

    public static final String GLOBAL = "global";

    private AiJobKeys() {
    }

    public static String of(String raw) {
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }
}
