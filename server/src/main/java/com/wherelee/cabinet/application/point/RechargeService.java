package com.wherelee.cabinet.application.point;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.wherelee.cabinet.application.point.dto.RechargeView;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizPayTxn;
import com.wherelee.cabinet.domain.enums.PayTxnStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizPayTxnMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 充值用例：下单 → 通道确认收款 → 幂等入账。
 *
 * <p><b>三段两个事务，中间那段不持事务</b>——与第 11 刀的设备指令同一条约束：
 * 通道调用是外部 I/O，把它关在 {@code @Transactional} 里会让每个"等待支付"的请求
 * 占着一个数据库连接（第 9 刀压测量出来的正是这个瓶颈）。
 *
 * <p>事务切法：① 写通道流水 CREATED；②（无事务）下单 + 查单；③ <b>markPaid 与点数入账同事务</b>。
 * 第 ③ 段为什么必须合在一起：这两行分别属于两本账，分开提交就会出现"通道说收了钱、
 * 点数没加"或反过来——那正是对账要抓的差异，<b>但不能是我们自己造出来的</b>。
 *
 * <p>失败方向一律保守：<b>通道没确认收款就绝不入账</b>。宁可用户投诉"付了没到账"
 * （可由对账与查单补），也不能让点数被凭空造出来——后者是平台资损，而且是静默的。
 */
@Service
public class RechargeService {

    private static final Logger log = LoggerFactory.getLogger(RechargeService.class);

    private final BizPayTxnMapper payTxnMapper;
    private final PointAccountService points;
    private final OrderFundService funds;
    private final TransactionTemplate txTemplate;
    /** 通道可以完全没有实现（prod 默认不装配 mock）：用 ObjectProvider，缺失时明确拒绝而不是启动崩。 */
    private final ObjectProvider<PayChannel> channelProvider;

    /** 单笔上限（点数=分）：没有上限的一个充值接口就是刷金额的入口（10 万点 = 1000 元）。 */
    @Value("${cabinet.pay.max-points-per-txn:100000}")
    private long maxPointsPerTxn;

    public RechargeService(BizPayTxnMapper payTxnMapper, PointAccountService points,
                           OrderFundService funds, TransactionTemplate txTemplate,
                           ObjectProvider<PayChannel> channelProvider) {
        this.payTxnMapper = payTxnMapper;
        this.points = points;
        this.funds = funds;
        this.txTemplate = txTemplate;
        this.channelProvider = channelProvider;
    }

    public RechargeView recharge(Long customerId, long requestPoints) {
        if (requestPoints <= 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "充值点数必须为正数");
        }
        if (requestPoints > maxPointsPerTxn) {
            throw new BizException(ResultCode.PARAM_INVALID,
                    "单笔最多 " + maxPointsPerTxn + " 点，请分次充值");
        }
        PayChannel channel = channelProvider.getIfAvailable();
        if (channel == null) {
            // 不是"功能没开"而是"收了钱没法记账"：这里必须拒绝，不能退化成直接加点数
            throw new BizException(ResultCode.MIDDLEWARE_UNAVAILABLE, "支付通道未配置，暂时无法充值");
        }

        // 商户订单号由服务端生成：客户端传什么都不能决定幂等号，否则重放就能造出多笔入账
        String outTradeNo = "RC" + IdWorker.getId();
        Long payTxnId = txTemplate.execute(status -> {
            BizPayTxn txn = new BizPayTxn();
            txn.setCustomerId(customerId);
            txn.setOutTradeNo(outTradeNo);
            txn.setChannel(channel.name());
            txn.setPoints(requestPoints);
            // 1 点 = 1 分（S-01）：换算规则只在这一处，不散落到各层
            txn.setAmountFen(requestPoints);
            txn.setStatus(PayTxnStatus.CREATED);
            payTxnMapper.insert(txn);
            return txn.getId();
        });

        String tradeNo = channel.createOrder(outTradeNo, requestPoints, requestPoints);
        if (tradeNo == null) {
            txTemplate.executeWithoutResult(status -> payTxnMapper.markFailed(payTxnId));
            log.warn("充值未收款，不入账 outTradeNo={} customerId={} points={}",
                    outTradeNo, customerId, requestPoints);
            return new RechargeView(outTradeNo, requestPoints, 0L, channel.name(), PayTxnStatus.FAILED.name());
        }

        // 查单而不是"下单即成功"：真实通道在这里可能返回未付，那就不该入账
        PayChannel.PayOutcome outcome = channel.query(outTradeNo);
        if (!outcome.paid()) {
            return new RechargeView(outTradeNo, requestPoints, 0L, channel.name(), PayTxnStatus.CREATED.name());
        }

        Long creditedBalance = txTemplate.execute(status -> {
            // 条件更新：0 行说明已经有别的路径（回调/重试）确认过这单，本次不能再入一次
            if (payTxnMapper.markPaid(payTxnId, outcome.tradeNo(), LocalDateTime.now()) == 0) {
                throw new BizException(ResultCode.IDEMPOTENT_REJECT, "该笔收款已处理，请刷新查看余额");
            }
            var result = points.recharge(customerId, requestPoints, outTradeNo, channel.name() + " 通道充值");
            if (!result.duplicated()) {
                log.info("充值入账成功 outTradeNo={} customerId={} points={} 余额={}",
                        outTradeNo, customerId, requestPoints, result.balanceAfter());
            }
            return result.balanceAfter();
        });

        // 欠费清偿放在入账事务**之外**：钱已收进来是既成事实，不得因为抵扣失败而把充值一起回滚
        // （那会变成“收了钱不入账”的资损方向）。失败只记日志：欠费依旧 > 0，下次充值会重试。
        long after = creditedBalance == null ? 0L : creditedBalance;
        try {
            long repaid = funds.repayArrears(customerId);
            if (repaid > 0) {
                after = Math.max(0L, after - repaid);
                log.info("充值附带清偿欠费 customerId={} 清偿={} 余额现={}", customerId, repaid, after);
            }
        } catch (RuntimeException e) {
            log.warn("充值成功但欠费清偿失败（欠费仍挡单，下次充值重试）customerId={}", customerId, e);
        }

        return new RechargeView(outTradeNo, requestPoints, after,
                channel.name(), PayTxnStatus.PAID.name());
    }
}
