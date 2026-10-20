package com.wherelee.cabinet.application.storage;
import com.wherelee.cabinet.locker.app.SlotCandidateQuery;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 取件码生成：<b>8 位随机，全项目唯一口径</b>。
 *
 * <p>为什么不用订单号后几位：订单号是雪花 ID，相邻注册即可猜测，等于把别人的行李交给任何人。
 *
 * <p>为什么是 8 位而不是 6 位：{@code uk_order_voucher} 是全表唯一，而订单表不参与归档
 * （码位只增不减）。6 位只有 90 万个可能，堆到十万单后每单约 11% 概率撞码，而撞码就是整单
 * 下单失败；9 千万个可能 + 下单换码重试，才能把这件事降到"事实上不会发生"。
 *
 * <p>抽成独立类的原因：同步下单与预扣异步落库两条路径原先各造各的码（一边 8 位一边 6 位），
 * 位数差一百倍的撞码率。凭据的形态必须只有一个定义——和候选格口判据收敛成
 * {@code SlotCandidateQuery} 是同一条理由。
 */
public final class VoucherCodes {

    private VoucherCodes() {
    }

    public static String newVoucher() {
        int random = ThreadLocalRandom.current().nextInt(10000000, 100000000);
        return String.format("V%08d", random);
    }
}
