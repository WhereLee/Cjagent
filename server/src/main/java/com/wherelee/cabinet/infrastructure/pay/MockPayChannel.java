package com.wherelee.cabinet.infrastructure.pay;

import com.wherelee.cabinet.funds.app.PayChannel;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "cabinet.pay.channel", havingValue = "mock")
public class MockPayChannel implements PayChannel {

    private static final Logger log = LoggerFactory.getLogger(MockPayChannel.class);

    private final Map<String, String> tradeByOutTradeNo = new ConcurrentHashMap<>();

    private volatile boolean failNext = false;

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public String createOrder(String outTradeNo, long points, long amountFen) {
        if (failNext) {
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

    public void setFailNext(boolean failNext) {
        this.failNext = failNext;
    }

    public void reset() {
        tradeByOutTradeNo.clear();
        failNext = false;
    }
}
