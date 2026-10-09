package com.wherelee.cabinet.application.point.dto;

import com.wherelee.cabinet.domain.enums.PayTxnStatus;

/**
 * 充值结果（给用户看的，不含任何通道内部字段）。
 *
 * <p>{@code status} 必须回给前端：{@code CREATED}/{@code FAILED} 与 {@code PAID} 是三种不同的提示
 * （"支付确认中"/"支付失败"/"已到账"）。只回一个"成功/失败"就会逼着前端自己猜余额，
 * 而余额是钱，猜错就是纠纷。
 *
 * @param outTradeNo 商户订单号（排查与对账的唯一入口，客服要的就是它）
 * @param points     本单购买点数
 * @param balance    入账后的可用点数（未入账时为 0，不是"不知道"）
 * @param channel    通道标识
 * @param status     通道流水状态
 */
public record RechargeView(String outTradeNo, long points, long balance,
                           String channel, String status) {

    public boolean credited() {
        return PayTxnStatus.PAID.name().equals(status);
    }
}
