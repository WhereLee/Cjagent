package com.wherelee.cabinet.funds.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingNumbersTest {

    private static Map<String, Object> cabinetPricing() throws Exception {
        Path file = Path.of("src/main/resources/application.yml");
        assertNotNull(file, "找不到 application.yml");
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> cabinet = (Map<String, Object>) root.get("cabinet");
            assertNotNull(cabinet, "application.yml 里没有 cabinet 段");
            Map<String, Object> pricing = (Map<String, Object>) cabinet.get("pricing");
            assertNotNull(pricing, "application.yml 里没有 cabinet.pricing 段");
            return pricing;
        }
    }

    @Test
    @DisplayName("单价、预收、免费窗口、封顶都是用户 2026-10-11 给的原数：小2/中4/大6元，预收 4 小时，30 分钟免费，封顶 10h/日与 48h")
    void userGivenNumbersAreInEffect() throws Exception {
        Map<String, Object> pricing = cabinetPricing();
        Map<String, Object> units = (Map<String, Object>) pricing.get("unit-points-per-hour");

        assertEquals(200, ((Number) units.get("SMALL")).intValue(), "小格口每小时应是 2 元 = 200 点");
        assertEquals(400, ((Number) units.get("MEDIUM")).intValue(), "中格口每小时应是 4 元 = 400 点");
        assertEquals(600, ((Number) units.get("LARGE")).intValue(), "大格口每小时应是 6 元 = 600 点");

        assertEquals(4, ((Number) pricing.get("prepay-hours")).intValue(), "预收时长是用户定的 4 小时");

        assertEquals(30, ((Number) pricing.get("free-minutes")).intValue(), "免费窗口是用户定的 30 分钟");

        assertEquals(10, ((Number) pricing.get("daily-cap-hours")).intValue(), "单日按 10 小时计");
        assertEquals(2, ((Number) pricing.get("cap-days")).intValue(), "封顶 48 小时 = 2 个计费日");
    }

    @Test
    @DisplayName("押金不再存在于配置里（预收取代账户押金）")
    void depositConfigIsGone() throws Exception {
        Map<String, Object> pricing = cabinetPricing();
        assertNull(pricing.get("account-deposit-points"),
                "账户押金整套已作废，不得再回到配置里——它与预收是两套重复担保");
        assertNull(pricing.get("deposit-points"), "旧“每单押金”也不得回来");
    }

    @Test
    @DisplayName("门未关阶梯就是用户给的 3/5/10 分钟；旧的 tolerance-minutes 已不再是计费参数")
    void doorWatchLadderNumbers() throws Exception {
        Path file = Path.of("src/main/resources/application.yml");
        Map<String, Object> root;
        try (java.io.InputStream in = Files.newInputStream(file)) {
            root = new Yaml().load(in);
        }
        Map<String, Object> cabinet = (Map<String, Object>) root.get("cabinet");
        Map<String, Object> watch = (Map<String, Object>) cabinet.get("door-watch");
        assertNotNull(watch, "19D 的提醒阶梯必须存在于 cabinet.door-watch");

        assertEquals(3, ((Number) watch.get("first-remind-minutes")).intValue(), "第一次提醒是用户定的 3 分钟");
        assertEquals(5, ((Number) watch.get("repeat-remind-minutes")).intValue(), "连发提醒从用户定的 5 分钟起");
        assertEquals(10, ((Number) watch.get("escalate-minutes")).intValue(), "必须处理是用户定的 10 分钟");
        assertEquals(3, ((Number) watch.get("repeat-times")).intValue(), "连发三条就是三条");

        assertNull(cabinetPricing().get("tolerance-minutes"),
                "tolerance-minutes 不得回来：计费起点是开柜瞬间，不再需要容错期参数");
    }
}
