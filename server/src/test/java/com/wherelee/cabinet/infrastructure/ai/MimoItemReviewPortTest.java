package com.wherelee.cabinet.infrastructure.ai;

import com.wherelee.cabinet.application.storage.ItemReviewPort;
import com.wherelee.cabinet.domain.enums.Presence;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 识图复审的解析与真实连通性。
 *
 * <p>分两半是刻意的：<b>解析部分必须每次门禁都跑</b>（它决定"模型胡说八道时会不会被当成凭据"），
 * 而真实调用只在本地给了 key 才跑——CI 里跑外部模型意味着结论不可重现、会撞限流，
 * 还会把一次网络抖动变成构建红。
 */
class MimoItemReviewPortTest {

    @Test
    @DisplayName("正常 JSON：三种结论都按字面解析")
    void parsesCleanJson() {
        assertEquals(Presence.PRESENT, MimoItemReviewPort.parse(
                "{\"presence\":\"PRESENT\",\"confidence\":0.99,\"reason\":\"多出一只蓝色箱子\"}").presence());
        assertEquals(Presence.ABSENT, MimoItemReviewPort.parse(
                "{\"presence\":\"ABSENT\",\"confidence\":1.0,\"reason\":\"两图都空\"}").presence());
        assertEquals(Presence.UNKNOWN, MimoItemReviewPort.parse(
                "{\"presence\":\"UNKNOWN\",\"confidence\":0.4,\"reason\":\"角度差太多\"}").presence());
    }

    /**
     * 实测踩到的那条：思考模型把 token 预算占满时，正文是<b>空字符串</b>。
     *
     * <p>如果代码把"没内容"读成"没东西"，就等于模型一超时就把用户的柜子判定为空、
     * 随后被分给下一个人。所以这里必须是 UNKNOWN，而不是 ABSENT。
     */
    @Test
    @DisplayName("空正文（思考 token 占满预算）必须是 UNKNOWN，不能当成【没东西】")
    void emptyContentIsUnknownNotAbsent() {
        assertEquals(Presence.UNKNOWN, MimoItemReviewPort.parse("").presence());
        assertEquals(Presence.UNKNOWN, MimoItemReviewPort.parse(null).presence());
        assertEquals(Presence.UNKNOWN, MimoItemReviewPort.parse("   ").presence());
        assertTrue(MimoItemReviewPort.parse("").note().contains("空正文"), "要给运维看得懂的原因");
    }

    @Test
    @DisplayName("脏格式：代码块包裹、前后闲话、字段缺失、不可识别枚举一律 UNKNOWN")
    void malformedOutputDegradesToUnknown() {
        // 模型很爱加 ```json 围栏
        assertEquals(Presence.PRESENT, MimoItemReviewPort.parse(
                "```json\n{\"presence\":\"PRESENT\",\"confidence\":0.8}\n```").presence());
        // 前面带一句废话
        assertEquals(Presence.ABSENT, MimoItemReviewPort.parse(
                "好的，判断如下：{\"presence\":\"ABSENT\",\"confidence\":0.95}").presence());
        // 完全不是 JSON
        assertEquals(Presence.UNKNOWN, MimoItemReviewPort.parse("格口里似乎有一个物体").presence());
        // 枚举值不可识别（模型会自己发明 "DIRTY"、"PARTIAL" 之类）
        assertEquals(Presence.UNKNOWN, MimoItemReviewPort.parse(
                "{\"presence\":\"PARTIAL\",\"confidence\":0.7}").presence());
        // 没给置信度：解析层按字面返回 PRESENT（它只管形状），不可信由阈值层降——见下面那条用例
        assertEquals(Presence.PRESENT, MimoItemReviewPort.parse("{\"presence\":\"PRESENT\"}").presence());
    }

    @Test
    @DisplayName("置信度低于阈值时降为 UNKNOWN——模型会用肯定的语气说不确定的事")
    void lowConfidenceIsDowngraded() {
        MimoItemReviewPort port = new MimoItemReviewPort();
        ReflectionTestUtils.setField(port, "minConfidence", new BigDecimal("0.6"));

        ItemReviewPort.Review weak = port.withThreshold(
                MimoItemReviewPort.parse("{\"presence\":\"PRESENT\",\"confidence\":0.42,\"reason\":\"反光\"}"));
        assertEquals(Presence.UNKNOWN, weak.presence(), "0.42 的 PRESENT 不能当凭据用");
        assertTrue(weak.note().contains("阈值"));

        ItemReviewPort.Review strong = port.withThreshold(
                MimoItemReviewPort.parse("{\"presence\":\"PRESENT\",\"confidence\":0.93,\"reason\":\"多出箱子\"}"));
        assertEquals(Presence.PRESENT, strong.presence());

        // 没给置信度同样不可信：“模型说了但没说多确定”不能当凭据
        assertEquals(Presence.UNKNOWN, port.withThreshold(
                MimoItemReviewPort.parse("{\"presence\":\"PRESENT\"}")).presence(),
                "没置信度的 PRESENT 必须降级，否则模型少给一个字段就等于白拿一张放行凭据");
    }

    @Test
    @DisplayName("缺任一张照片就不发请求：没有基准照的比对只会凭空造出凭据")
    void missingPhotoSkipsCallEntirely() {
        MimoItemReviewPort port = new MimoItemReviewPort();
        ItemReviewPort.Review verdict = port.review(new ItemReviewPort.Request(
                1L, 2L, "S01", "file:/tmp/closed.jpg", null));
        assertEquals(Presence.UNKNOWN, verdict.presence());
        assertTrue(verdict.note().contains("基准"), "原因要写给运维看：" + verdict.note());
    }

    /**
     * 真实连通性：只有本机设了 MIMO_API_KEY 才跑。
     *
     * <p>它验的是"我们的请求形状这个网关认不认"（认证头、多模态 content 数组、
     * max_completion_tokens 的语义），这些光靠桩测不出来。图用本地两张同格口照片。
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "MIMO_API_KEY", matches = ".+")
    @DisplayName("真实调用：空柜基准 vs 有行李，应判 PRESENT")
    void liveCallJudgesDifference() {
        Path empty = Path.of("C:/Users/lrs/.qoder-cn/vibe_images/locker-empty_1791551783.png");
        Path withBag = Path.of("C:/Users/lrs/.qoder-cn/vibe_images/locker-with-suitcase_1791551783.png");
        Assumptions.assumeTrue(Files.exists(empty) && Files.exists(withBag), "本地没有测试照片，跳过真实调用");

        MimoItemReviewPort port = new MimoItemReviewPort();
        ReflectionTestUtils.setField(port, "baseUrl", "https://api.xiaomimimo.com/v1");
        ReflectionTestUtils.setField(port, "apiKey", System.getenv("MIMO_API_KEY"));
        ReflectionTestUtils.setField(port, "model", "mimo-v2.6-flash");
        ReflectionTestUtils.setField(port, "timeoutMs", 30_000L);
        ReflectionTestUtils.setField(port, "maxCompletionTokens", 1200);
        ReflectionTestUtils.setField(port, "minConfidence", new BigDecimal("0.6"));

        ItemReviewPort.Review verdict = port.review(new ItemReviewPort.Request(
                1L, 2L, "S01", withBag.toString(), empty.toString()));
        assertNotNull(verdict.presence());
        assertEquals(Presence.PRESENT, verdict.presence(),
                "真实调用应认出多出的行李，实际：" + verdict.presence() + " " + verdict.note());
    }
}
