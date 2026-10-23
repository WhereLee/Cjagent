package com.wherelee.cabinet.assistant.app;

import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * 作业请求键：把入参压成唯一索引列放得下的摘要。
 *
 * <p>{@code request_key} 列宽 128，而问题的上限是 500 字——原文放不下，也不该放：
 * 那一列参与唯一索引，把正文塞进索引只会让它变胖、比对变慢。
 *
 * <p>用 MD5 只因为它短且稳定，<b>不做任何安全用途</b>。在这个场景里碰撞没有攻击面：
 * 同一个人两个不同问题撞了，最坏结果是复用了一个还在跑的作业，而他本来也点不了第二次
 * （{@code active_flag} 唯一键会拦住）。
 */
public final class AiJobKeys {

    /** 无入参的作业（运维诊断）用固定串：只要上一条没跑完就复用。 */
    public static final String GLOBAL = "global";

    private AiJobKeys() {
    }

    public static String of(String raw) {
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }
}
