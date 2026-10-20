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

/**
 * 把用户定下的价格数字钉在配置文件上。
 *
 * <p><b>为什么单独有这一条</b>：集成测试环境为了让几十处与钱无关的用例保持稳定，
 * 沿用了一组较小的金额（见 {@code application-integration.yml} 里的说明）。那就意味着
 * "线上到底收多少钱"这件事没有任何东西在守——而它恰恰是已经被我改动过一次的地方：
 * 用户说小中大 4/6/8，我落成了 15/25/40 点，还在汇报里用点数复述，导致错误口径活了一整套测试。
 *
 * <p>所以这里不测算法（{@link PricingPolicyTest} 管算法），只测一件事：
 * <b>配置文件里写的就是用户给的那几个数</b>。要改任何一个，必须连这里一起改，
 * 并在改动说明里写清是谁批准的——不允许悄悄换一个数。
 */
class PricingNumbersTest {

    private static Map<String, Object> cabinetPricing() throws Exception {
        // surefire 的工作目录是 server/，读的就是打进 jar 的那份配置源文件
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

        // 1 点 = 1 分（S-01）：2/4/6 元 = 200/400/600 点
        assertEquals(200, ((Number) units.get("SMALL")).intValue(), "小格口每小时应是 2 元 = 200 点");
        assertEquals(400, ((Number) units.get("MEDIUM")).intValue(), "中格口每小时应是 4 元 = 400 点");
        assertEquals(600, ((Number) units.get("LARGE")).intValue(), "大格口每小时应是 6 元 = 600 点");

        // 预收四小时：下单先收 4 × 单价（小 8 / 中 16 / 大 24 元），结束时多退少补
        assertEquals(4, ((Number) pricing.get("prepay-hours")).intValue(), "预收时长是用户定的 4 小时");

        // 免费窗口：首次开柜后前 30 分钟不计费（不分门有没有关）
        assertEquals(30, ((Number) pricing.get("free-minutes")).intValue(), "免费窗口是用户定的 30 分钟");

        // 封顶：每 24 小时只收 10 小时价，总计费封顶 48 小时（2 个计费日）→ 最多 20 小时单价
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

        // 用户 2026-10-11 原话：3 分钟提醒、5 分钟连发三条、10 分钟业务员必须处理
        assertEquals(3, ((Number) watch.get("first-remind-minutes")).intValue(), "第一次提醒是用户定的 3 分钟");
        assertEquals(5, ((Number) watch.get("repeat-remind-minutes")).intValue(), "连发提醒从用户定的 5 分钟起");
        assertEquals(10, ((Number) watch.get("escalate-minutes")).intValue(), "必须处理是用户定的 10 分钟");
        assertEquals(3, ((Number) watch.get("repeat-times")).intValue(), "连发三条就是三条");

        // 旧的单一容错参数已拆掉：它过去既当计费起点又当提醒时刻（19A 后前者已不存在）
        assertNull(cabinetPricing().get("tolerance-minutes"),
                "tolerance-minutes 不得回来：计费起点是开柜瞬间，不再需要容错期参数");
    }
}
