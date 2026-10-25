package com.wherelee.cabinet.storage.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Service
public class OrderVoucherService {

    private static final Logger log = LoggerFactory.getLogger(OrderVoucherService.class);

    private static final int VOUCHER_MAX_WRONG = 5;

    private static final int VOUCHER_RETRIES = 3;

    static final String LOCK_MESSAGE = "取件码连续输错次数过多，本单开柜已锁定；"
            + "可在订单里重置取件码自助解锁，或联系客服核验后处理";

    private final BizStorageOrderMapper orderMapper;
    private final TransactionTemplate newTx;

    public OrderVoucherService(BizStorageOrderMapper orderMapper, TransactionTemplate txTemplate) {
        this.orderMapper = orderMapper;
        this.newTx = new TransactionTemplate(txTemplate.getTransactionManager());
        this.newTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void insert(BizStorageOrder order) {
        for (int attempt = 1; ; attempt++) {
            try {
                orderMapper.insert(order);
                return;
            } catch (org.springframework.dao.DuplicateKeyException collision) {
                boolean voucherClash = String.valueOf(collision.getMessage()).contains("uk_order_voucher");
                if (!voucherClash || attempt >= VOUCHER_RETRIES) {
                    throw collision;
                }
                order.setVoucherCode(VoucherCodes.newVoucher());
                log.warn("取件码撞号，换码重试 orderNo={} 第 {} 次", order.getOrderNo(), attempt);
            }
        }
    }

    public String noteForNewOrder(String voucherCode, String slotNo) {
        return "格口 " + slotNo + " 已开，取件码 " + voucherCode
                + "——手机没电时可凭它与手机尾号在柜机屏取件，请记下或截图";
    }

    public String noteForExisting(String voucherCode, String slotNo) {
        return "取件码 " + voucherCode + "（格口 " + slotNo
                + "）——手机没电时可凭它与手机尾号在柜机屏取件；泄露或忘记可在订单里重置";
    }

    public void verify(BizStorageOrder order, String voucherCode) {
        requireNotLocked(order);
        if (voucherCode == null || voucherCode.isBlank()) {
            return;
        }
        if (voucherCode.trim().equals(order.getVoucherCode())) {
            if (order.getVoucherWrongCount() != null && order.getVoucherWrongCount() > 0) {
                order.setVoucherWrongCount(0);
                if (orderMapper.updateById(order) == 0) {
                    log.warn("取件码验对后清零错次落空，错次可能残留 orderNo={} 待后续写入补齐",
                            order.getOrderNo());
                }
            }
            return;
        }
        int counted = registerWrong(order);
        throw new BizException(ResultCode.BIZ_ERROR,
                "取件码不正确（还剩 " + (VOUCHER_MAX_WRONG - counted) + " 次机会）");
    }

    public int registerWrong(BizStorageOrder order) {
        int wrong = (order.getVoucherWrongCount() == null ? 0 : order.getVoucherWrongCount()) + 1;
        boolean lock = wrong >= VOUCHER_MAX_WRONG;
        LocalDateTime at = lock ? LocalDateTime.now() : null;
        final int counted = wrong;
        newTx.executeWithoutResult(status -> {
            BizStorageOrder fresh = orderMapper.selectById(order.getId());
            if (fresh == null) {
                return;
            }
            fresh.setVoucherWrongCount(counted);
            if (at != null) {
                fresh.setVoucherLockedAt(at);
            }
            orderMapper.updateById(fresh);
        });
        order.setVoucherWrongCount(counted);
        if (lock) {
            order.setVoucherLockedAt(at);
            log.warn("取件码连续输错 {} 次，锁定该单开柜能力 orderNo={} customer={}",
                    counted, order.getOrderNo(), order.getCustomerId());
            throw new BizException(ResultCode.BIZ_ERROR, LOCK_MESSAGE);
        }
        return counted;
    }

    public void requireNotLocked(BizStorageOrder order) {
        if (order.getVoucherLockedAt() != null) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "该单因取件码连续输错已锁定开柜，可在订单里重置取件码自助解锁；"
                            + "或联系客服核验（后台可强制开柜并留审计）");
        }
    }

    public String rotate(BizStorageOrder order) {
        for (int attempt = 1; ; attempt++) {
            String fresh = VoucherCodes.newVoucher();
            try {
                int updated = orderMapper.update(null, Wrappers.<BizStorageOrder>lambdaUpdate()
                        .eq(BizStorageOrder::getId, order.getId())
                        .eq(BizStorageOrder::getVersion, order.getVersion())
                        .set(BizStorageOrder::getVoucherCode, fresh)
                        .set(BizStorageOrder::getVoucherWrongCount, 0)
                        .set(BizStorageOrder::getVoucherLockedAt, null)
                        .set(BizStorageOrder::getVersion, order.getVersion() + 1));
                if (updated == 0) {
                    throw new BizException(ResultCode.SYSTEM_ERROR,
                            "订单已被并发修改，请重试 orderNo=" + order.getOrderNo());
                }
                return fresh;
            } catch (org.springframework.dao.DuplicateKeyException collision) {
                if (!String.valueOf(collision.getMessage()).contains("uk_order_voucher")
                        || attempt >= VOUCHER_RETRIES) {
                    throw collision;
                }
                log.warn("重置时取件码撞号，换码重试 orderNo={} 第 {} 次", order.getOrderNo(), attempt);
            }
        }
    }
}
