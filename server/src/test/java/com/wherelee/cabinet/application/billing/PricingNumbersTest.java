package com.wherelee.cabinet.application.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    @DisplayName("单价与押金就是用户 2026-10-10 给的数：小 4 元、中 7 元、大 10 元每小时，押金 50 元")
    void userGivenNumbersAreInEffect() throws Exception {
        Map<String, Object> pricing = cabinetPricing();
        Map<String, Object> units = (Map<String, Object>) pricing.get("unit-points-per-hour");

        // 1 点 = 1 分（S-01）：4 元 = 400 点，7 元 = 700 点，10 元 = 1000 点，50 元 = 5000 点
        assertEquals(400, ((Number) units.get("SMALL")).intValue(), "小格口每小时应是 4 元 = 400 点");
        assertEquals(700, ((Number) units.get("MEDIUM")).intValue(), "中格口每小时应是 7 元 = 700 点");
        assertEquals(1000, ((Number) units.get("LARGE")).intValue(), "大格口每小时应是 10 元 = 1000 点");
        assertEquals(5000, ((Number) pricing.get("account-deposit-points")).intValue(),
                "账户押金应是 50 元 = 5000 点（交一次长期押着，不参与消费）");
    }

    @Test
    @DisplayName("容错 5 分钟、远程加收 2 小时是用户给的数；免费窗口与封顶是我定的，改动要说明理由")
    void timingAndPenaltyNumbers() throws Exception {
        Map<String, Object> pricing = cabinetPricing();

        // 用户原话（2026-10-09）：开门留 5 分钟容错；远程截止账单收该格口两个小时的费用
        assertEquals(5, ((Number) pricing.get("tolerance-minutes")).intValue(), "容错期是用户定的 5 分钟");
        assertEquals(2, ((Number) pricing.get("remote-close-hours")).intValue(), "远程结束加收是用户定的 2 小时");

        // 这两个是我定的数，用户 2026-10-10 明确同意保留；要改必须先问
        assertEquals(10, ((Number) pricing.get("free-minutes")).intValue(), "关门起算后的免费窗口（代理定的数）");
        assertEquals(12, ((Number) pricing.get("daily-cap-hours")).intValue(), "单日封顶小时数（代理定的数）");
        assertEquals(3, ((Number) pricing.get("cap-days")).intValue(), "总额封顶天数（代理定的数）");
    }
}
