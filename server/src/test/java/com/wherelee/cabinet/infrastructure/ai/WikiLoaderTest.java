package com.wherelee.cabinet.infrastructure.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * wiki 装载（第 22 刀）：agent 的知识底座必须在 classpath 上齐全——
 * 少一页，模型对那一类问题就没有口径可依，只会现编。
 */
class WikiLoaderTest {

    @Test
    @DisplayName("五页 wiki 全部装载：规则页、术语页与三份 FAQ 一个不能少")
    void allPagesLoaded() {
        String wiki = WikiLoader.wiki();

        assertTrue(wiki.contains("AGENTS.md"), "角色边界页缺失");
        assertTrue(wiki.contains("glossary.md"), "术语页缺失");
        assertTrue(wiki.contains("faq-billing.md"), "计费口径页缺失");
        assertTrue(wiki.contains("faq-door.md"), "开门处置脚本缺失");
        assertTrue(wiki.contains("faq-general.md"), "常见问题页缺失");
    }

    @Test
    @DisplayName("关键口径抽查：铁律与计费封顶必须在场")
    void keyFactsPresent() {
        String wiki = WikiLoader.wiki();

        assertTrue(wiki.contains("铁律"), "AGENTS.md 的铁律是 agent 的边界所在");
        assertTrue(wiki.contains("48 小时封顶"), "计费封顶口径必须在场");
        assertTrue(wiki.contains("DEVICE_FAULT"), "建单类型白名单必须在场");
    }

    @Test
    @DisplayName("重复调用返回同一份缓存：wiki 是静态资产，不该每轮对话重读")
    void cachedAcrossCalls() {
        assertSame(WikiLoader.wiki(), WikiLoader.wiki());
        assertEquals(WikiLoader.wiki(), WikiLoader.wiki());
    }
}
