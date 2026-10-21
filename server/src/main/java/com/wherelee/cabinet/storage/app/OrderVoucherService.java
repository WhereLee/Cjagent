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

/**
 * 取件凭据：生成、交付话术、校验、错次计数与锁定、重置。
 *
 * <p>为什么从订单用例里拆出来：凭据这条线有三条互相独立的路径（下单发码、开柜验码、
 * 自助重置），但它们共用同一套计数与锁定语义（定-25：连续错 5 次锁这张单的开柜能力）。
 * 散在 1000 行的订单服务里时，“哪条路径忘了计数”只能靠人记；收在一处后，判据只有一个。
 */
@Service
public class OrderVoucherService {

    private static final Logger log = LoggerFactory.getLogger(OrderVoucherService.class);

    /** 连续输错阈值——<b>5 次由用户 2026-10-10 给出</b>，不进配置（能配就会被误配成 0 或 99）。 */
    private static final int VOUCHER_MAX_WRONG = 5;

    /** 撞码重试次数：uk_order_voucher 冲突时换码重来，别的唯一索引冲突不许掩盖成重试。 */
    private static final int VOUCHER_RETRIES = 3;

    static final String LOCK_MESSAGE = "取件码连续输错次数过多，本单开柜已锁定；"
            + "可在订单里重置取件码自助解锁，或联系客服核验后处理";

    private final BizStorageOrderMapper orderMapper;
    /**
     * 错次计数要用**独立事务**写：错码后要抛异常，外层事务会回滚，
     * 同一事务里写的计数与锁定会跟着消失——结果是“永远数不到第五次，永远锁不上”却看不出来。
     */
    private final TransactionTemplate newTx;

    public OrderVoucherService(BizStorageOrderMapper orderMapper, TransactionTemplate txTemplate) {
        this.orderMapper = orderMapper;
        this.newTx = new TransactionTemplate(txTemplate.getTransactionManager());
        this.newTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 插单；只认 {@code uk_order_voucher} 这一种冲突：别的唯一索引报错（比如格口被抢先占了）
     * 重试多少次都一样，掩盖成“正在重试”反而让真正的并发原因查不出来。
     *
     * <p>不能重来整个下单：格口已经分配、预收已经扣过，重来会把一次码冲突放大成用户能感到的失败。
     */
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

    /**
     * 取件码交付话术（第 25 刀）：存件那一刻是唯一能把码交给用户的时点（手机还没电），
     * 所以要求“记下或截图”。凭码路径的找回对应物 = App 内查询端点 + 自助重置，不假装有短信。
     */
    public String noteForNewOrder(String voucherCode, String slotNo) {
        return "格口 " + slotNo + " 已开，取件码 " + voucherCode
                + "——手机没电时可凭它与手机尾号在柜机屏取件，请记下或截图";
    }

    /** 可回看的凭证文案（站内消息与重置返回共用：此刻门没开，不能再写“已开”）。 */
    public String noteForExisting(String voucherCode, String slotNo) {
        return "取件码 " + voucherCode + "（格口 " + slotNo
                + "）——手机没电时可凭它与手机尾号在柜机屏取件；泄露或忘记可在订单里重置";
    }

    /**
     * 校验凭据。
     *
     * <p>两个判据分开写：<b>锁定</b>是“错太多，暂停这条凭据”，<b>错</b>是“本次不对”——
     * 混在一起会变成“一输错就锁”或“锁了还继续给次数”。正确码会把计数归零
     * （所以上限是“连续”错 5 次，不是“一共”5 次）。
     */
    public void verify(BizStorageOrder order, String voucherCode) {
        requireNotLocked(order);
        if (voucherCode == null || voucherCode.isBlank()) {
            return;
        }
        if (voucherCode.trim().equals(order.getVoucherCode())) {
            // 成功路径不抛异常，所以留在外层事务里改（并把内存对象也改对，
            // 不然随后的 finish 会用陈旧的计数把零盖回去）
            if (order.getVoucherWrongCount() != null && order.getVoucherWrongCount() > 0) {
                order.setVoucherWrongCount(0);
                orderMapper.updateById(order);
            }
            return;
        }
        int counted = registerWrong(order);
        throw new BizException(ResultCode.BIZ_ERROR,
                "取件码不正确（还剩 " + (VOUCHER_MAX_WRONG - counted) + " 次机会）");
    }

    /**
     * 记一次凭据错误：到阈值就锁这张单的开柜能力，没到就返回当前错次。
     *
     * <p>写成方法而不是留在 {@code verify} 里：定-25 的第二条路径（取件码 + 手机尾号）
     * 要用**完全同一套计数**。复制两份就会有一边漏掉锁定，而“锁不住”正是这个机制唯一要防的事。
     * <p>先落库再报错：这两列要活过外层回滚，不然锁定永远不生效。
     */
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

    /**
     * 换一张新码并清掉锁定与错次，返回最终生效的码。
     *
     * <p>写 null 必须走显式 UPDATE：{@code voucher_locked_at} 没有 ALWAYS 注解，
     * 用 updateById 时 null 会被字段策略吞掉——**锁定解不掉却看不出来**。乐观锁手动带
     * version 条件（与 @Version 同一张判据）；撞码只重“换码”这一步。
     */
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
