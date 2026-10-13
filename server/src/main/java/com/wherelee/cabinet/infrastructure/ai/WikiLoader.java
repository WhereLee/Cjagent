package com.wherelee.cabinet.infrastructure.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * AI wiki 装载器（第 22 刀）：把 {@code classpath:ai-wiki/*.md} 读进内存，拼成 agent 的知识底座。
 *
 * <p>这是 Karpathy LLM-Wiki 三层结构的最小落地：wiki 是我们写给模型看的 markdown（业务口径、
 * 处置脚本、禁区），<b>它随代码一起走版本管理</b>——规则改了要过 review、要过 CI，
 * 而不是塞在模型记忆或某次对话里。每页头部 frontmatter 带 id 与版本，回答口径出问题时
 * 能查到"当时是哪一版在说话"。
 *
 * <p>文件按文件名排序拼接（AGENTS.md 排最前），启动后缓存：wiki 是静态资产，
 * 没有热更新需求——要改口径就改文件、发版。
 */
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
            // wiki 缺失时 agent 只会瞎编（没有口径可依），宁可启动失败也不带病上线
            throw new IllegalStateException("ai-wiki 资源读取失败：它是 agent 的知识底座，缺失即装配问题", e);
        }
    }
}
