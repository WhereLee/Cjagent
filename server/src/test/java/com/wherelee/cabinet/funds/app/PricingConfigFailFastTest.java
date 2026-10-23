package com.wherelee.cabinet.funds.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 定价参数缺配置必须起不来。
 *
 * <p>这条测试存在的唯一理由：{@link PricingPolicy} 的八个 {@code @Value} 全部不带默认值。
 * 上一版带默认值，而且停在作废旧价 15/25/40——集成测试走的是"配置生效"路径，
 * 恰好测不到默认值，那个隐患活了很久，是注释自己承认的。
 *
 * <p>光把 {@code :默认值} 删掉不够：要有一条测试证明"缺了真的起不来"，
 * 否则下次有人顺手给某个 {@code @Value} 加回默认值，没有任何东西会拦他。
 */
class PricingConfigFailFastTest {

    /** 八项必须与 application.yml 的 cabinet.pricing.* 逐项对应。 */
    private static final String[] ALL_KEYS = {
            "cabinet.pricing.prepay-hours=4",
            "cabinet.pricing.free-minutes=30",
            "cabinet.pricing.unit-points-per-hour.LARGE=600",
            "cabinet.pricing.unit-points-per-hour.MEDIUM=400",
            "cabinet.pricing.unit-points-per-hour.SMALL=200",
            "cabinet.pricing.daily-cap-hours=10",
            "cabinet.pricing.cap-days=2",
            "cabinet.pricing.remote-close-hours=2",
    };

    private static ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(PricingPolicy.class)
                // ApplicationContextRunner 默认不带占位符解析器：不注册它，@Value 一律解析不了
                .withBean(PropertySourcesPlaceholderConfigurer.class)
                .withPropertyValues(properties);
    }

    @Test
    @DisplayName("八项齐全时能正常装配")
    void startsWhenAllKeysPresent() {
        runner(ALL_KEYS).run(context -> {
            assertNull(context.getStartupFailure(), "八项齐全时不该启动失败");
            assertNotNull(context.getBean(PricingPolicy.class));
        });
    }

    @Test
    @DisplayName("缺任意一项就起不来：不允许带着兜底旧价启动")
    void failsWhenAnyKeyMissing() {
        for (String missing : ALL_KEYS) {
            String key = missing.substring(0, missing.indexOf('='));
            String[] rest = Arrays.stream(ALL_KEYS).filter(k -> !k.equals(missing)).toArray(String[]::new);

            runner(rest).run(context -> assertNotNull(context.getStartupFailure(),
                    "缺少 " + key + " 时上下文居然起来了——说明又有人给 @Value 加回了默认值"));
        }
        assertEquals(8, ALL_KEYS.length, "八项要覆盖 PricingPolicy 的全部 @Value，加了新参数请同步这里");
    }
}
