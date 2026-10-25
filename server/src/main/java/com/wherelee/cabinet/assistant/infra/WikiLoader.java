package com.wherelee.cabinet.assistant.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

public final class WikiLoader {

    private static final Logger log = LoggerFactory.getLogger(WikiLoader.class);

    private static volatile String cached;

    private WikiLoader() {
    }

    public static String wiki() {
        if (cached == null) {
            synchronized (WikiLoader.class) {
                if (cached == null) {
                    cached = load();
                }
            }
        }
        return cached;
    }

    private static String load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:ai-wiki/*.md");
            Arrays.sort(resources, Comparator.comparing(Resource::getFilename,
                    Comparator.nullsLast(String::compareTo)));
            StringBuilder sb = new StringBuilder();
            for (Resource resource : resources) {
                sb.append("===== ").append(resource.getFilename()).append(" =====\n")
                        .append(new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8))
                        .append("\n\n");
            }
            log.info("AI wiki 已装载 {} 页，共 {} 字符", resources.length, sb.length());
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("ai-wiki 资源读取失败：它是 agent 的知识底座，缺失即装配问题", e);
        }
    }
}
