package com.wherelee.cabinet.application.point;

/**
 * 支付通道端口：把"收一笔钱"这件事抽象出来。
 *
 * <p>与设备通道（第 11 刀）同一个理由：<b>通道会变，账务不能变</b>。接真实支付宝/微信时
 * 只替换实现与回调入口，{@code RechargeService} 的"下单 → 确认收款 → 幂等入账"这条链一行不改。
 *
 * <p>故意拆成 {@link #createOrder} 与 {@link #query} 两步而不是一个"支付成功"回调：
 * 真实世界回调可能永不到达（个人项目没有公网可达地址，S-11 已把真实通道推到最后），
 * <b>只有"主动查单"这条路能让充值在回调缺失时也闭环</b>。将来接回调只是多一个入口，
 * 入账判据仍然是同一个（幂等号 + 条件更新）。
 *
 * <p>实现必须满足两条，否则资金链路不成立：
 * ① 同一 {@code outTradeNo} 重复调用 {@link #query} 返回同一个结果（不能随机变）；
 * ② 任何"成功"都必须能被 {@code biz_pay_txn} 里的对手方记录解释——
 *    通道说收了钱却查不到记录，与查得到记录却没入账，都是对账要响的差异。
 */
public interface PayChannel {

    /** 通道标识，写进 biz_pay_txn.channel（进指标与日志，避免事后说不清是谁收的钱）。 */
    String name();

    /**
     * 下单。
     *
     * @param amountFen 应收金额（分）。1 点 = 1 分（S-01），整型，禁止浮点
     * @return 通道侧的收款标识（真实通道是 prepay_id / 支付链接）
     */
    String createOrder(String outTradeNo, long points, long amountFen);

    /** 主动查单：回调不可达时的闭环手段，也是定时对账的取数口。 */
    PayOutcome query(String outTradeNo);

    /**
     * @param paid      通道是否已确认收款
     * @param tradeNo   通道侧交易号
     * @param reason    未收款原因（{@code paid=false} 时必填进日志）
     */
    record PayOutcome(boolean paid, String tradeNo, String reason) {

        public static PayOutcome paid(String tradeNo) {
            return new PayOutcome(true, tradeNo, null);
        }

        public static PayOutcome notYet(String reason) {
            return new PayOutcome(false, null, reason);
        }
    }
}
