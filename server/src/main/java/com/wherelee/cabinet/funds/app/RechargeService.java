package com.wherelee.cabinet.funds.app;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.wherelee.cabinet.funds.api.RechargePort;
import com.wherelee.cabinet.funds.api.RechargeView;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.funds.domain.BizPayTxn;
import com.wherelee.cabinet.funds.api.PayTxnStatus;
import com.wherelee.cabinet.funds.infra.BizPayTxnMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Service
public class RechargeService implements RechargePort {

    private static final Logger log = LoggerFactory.getLogger(RechargeService.class);

    private final BizPayTxnMapper payTxnMapper;
    private final PointAccountService points;
    private final OrderFundService funds;
    private final TransactionTemplate txTemplate;
    private final ObjectProvider<PayChannel> channelProvider;

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

    @Override
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
            throw new BizException(ResultCode.MIDDLEWARE_UNAVAILABLE, "支付通道未配置，暂时无法充值");
        }

        String outTradeNo = "RC" + IdWorker.getId();
        Long payTxnId = txTemplate.execute(status -> {
            BizPayTxn txn = new BizPayTxn();
            txn.setCustomerId(customerId);
            txn.setOutTradeNo(outTradeNo);
            txn.setChannel(channel.name());
            txn.setPoints(requestPoints);
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

        PayChannel.PayOutcome outcome = channel.query(outTradeNo);
        if (!outcome.paid()) {
            return new RechargeView(outTradeNo, requestPoints, 0L, channel.name(), PayTxnStatus.CREATED.name());
        }

        Long creditedBalance = txTemplate.execute(status -> {
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
