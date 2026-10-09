package com.wherelee.cabinet.infrastructure.pay;

import com.wherelee.cabinet.application.point.PayChannel;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock 支付通道：<b>它的职责是让"钱从通道到账本"这条链在开发态真的能跑通</b>，
 * 而不是假装自己有支付能力。
 *
 * <p>三条边界写清楚，免得日后有人把它当真实通道：
 * <ul>
 *   <li>不产生任何真实资金流动，也不接回调；</li>
 *   <li><b>默认装配条件是 {@code cabinet.pay.channel=mock}</b>：生产 yml 里显式不设这个值，
 *       于是 prod 进程里根本没有这个 bean（"安全默认拒绝"的同一取向：不靠运行时判断挡住，
 *       而是让它不存在）；</li>
 *   <li>同一 outTradeNo 的 {@link #query} 结果必须稳定——这条是所有通道实现的共同约定，
 *       在这里先用测试钉住，换真实实现时才不会发现账务要改。</li>
 * </ul>
 *
 * <p>它记住已下过的单（内存表）：模拟器的"可控失败"思路同样适用——
 * 需要演示"通道说没收到钱"时改一个开关就够，不需要真去支付。
 */
@Component
@ConditionalOnProperty(name = "cabinet.pay.channel", havingValue = "mock")
public class MockPayChannel implements PayChannel {

    private static final Logger log = LoggerFactory.getLogger(MockPayChannel.class);

    /** outTradeNo → 通道交易号。查单要能重复返回同一个结果，所以必须记住。 */
    private final Map<String, String> tradeByOutTradeNo = new ConcurrentHashMap<>();

    /** 测试/本地调试用：下一次下单标记为"未收款"，用来造对账差异。 */
    private volatile boolean failNext = false;

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public String createOrder(String outTradeNo, long points, long amountFen) {
        if (failNext) {
            // 故意不下账：模拟"用户没付/通道没确认"，此时入账必须被挡住
            log.warn("Mock 通道按开关拒绝收款 outTradeNo={} points={}", outTradeNo, points);
            return null;
        }
        String tradeNo = "MOCK" + IdWorker.getId();
        tradeByOutTradeNo.put(outTradeNo, tradeNo);
        log.info("Mock 通道收款 outTradeNo={} points={} amountFen={} tradeNo={}",
                outTradeNo, points, amountFen, tradeNo);
        return tradeNo;
    }

    @Override
    public PayOutcome query(String outTradeNo) {
        String tradeNo = tradeByOutTradeNo.get(outTradeNo);
        return tradeNo == null
                ? PayOutcome.notYet("通道侧查无此单（未下单或已超时关闭）")
                : PayOutcome.paid(tradeNo);
    }

    /** 测试与本地调试：让下一笔收款失败（不影响已在途的单）。 */
    public void setFailNext(boolean failNext) {
        this.failNext = failNext;
    }

    /** 测试清理：通道侧账本清零，避免用例间互相看到对方的单。 */
    public void reset() {
        tradeByOutTradeNo.clear();
        failNext = false;
    }
}
