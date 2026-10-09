package com.wherelee.cabinet.domain.enums;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 点数流水类型。
 *
 * <p><b>设计约束：一笔流水只影响一栏（{@link Bucket#POINTS 可用点数} 或
 * {@link Bucket#FROZEN 冻结点数}），且符号由类型唯一确定。</b>
 * 这样才能把"账平"写成两条纯求和断言：
 * <pre>
 *   points        == Σ amount where bucket = POINTS
 *   frozen_points == Σ amount where bucket = FROZEN
 * </pre>
 * 一旦允许某笔流水同时动两栏（常见的"冻结：可用减、冻结加"写成一条），
 * 上面两条就不再成立，余额校验只能靠人工推导——那是账务代码最难维护的形态。
 * 所以"冻结"在这里拆成 {@link #FREEZE_OUT} + {@link #FREEZE_IN} 两笔。
 *
 * <p>这是"轻档"记账（用户拍板）：<b>不做</b>双分录、本金/赠送分栏、退款折算、对账四分类。
 * 放弃的东西记在 docs/储物柜业务规划.md，不是没想到。
 */
public enum PointTxnType {

    /** 充值入账 */
    RECHARGE(Bucket.POINTS, Sign.PLUS),
    /** 人工调整，符号由 amount 决定（唯一允许任意符号的类型） */
    ADJUST(Bucket.POINTS, Sign.EITHER),
    /** 真实消耗：结算与补扣 */
    CONSUME(Bucket.POINTS, Sign.MINUS),
    /** 冻结：从可用移出 */
    FREEZE_OUT(Bucket.POINTS, Sign.MINUS),
    /** 冻结：进入冻结栏（与 FREEZE_OUT 成对） */
    FREEZE_IN(Bucket.FROZEN, Sign.PLUS),
    /** 解冻：从冻结栏移出 */
    UNFREEZE_OUT(Bucket.FROZEN, Sign.MINUS),
    /** 解冻：回到可用栏（与 UNFREEZE_OUT 成对；押金退还也走这一对） */
    UNFREEZE_IN(Bucket.POINTS, Sign.PLUS);

    /** 流水影响哪一栏。 */
    public enum Bucket { POINTS, FROZEN }

    /** 该类型允许的符号。 */
    public enum Sign { PLUS, MINUS, EITHER }

    /** 消耗类：需要真实扣减点数，可能因余额不足失败。 */
    private static final Set<PointTxnType> CONSUMING = EnumSet.of(CONSUME, FREEZE_OUT);

    private final Bucket bucket;
    private final Sign sign;

    PointTxnType(Bucket bucket, Sign sign) {
        this.bucket = bucket;
        this.sign = sign;
    }

    public Bucket bucket() {
        return bucket;
    }

    /** 把带符号的 amount 规范化成"该类型应有的符号"，防止调用方传错正负导致账越对越乱。 */
    public long normalize(long amount) {
        long abs = Math.abs(amount);
        return switch (sign) {
            case PLUS -> abs;
            case MINUS -> -abs;
            // ADJUST 保留原符号；其余类型强制归一
            case EITHER -> amount;
        };
    }

    /** 是否需要从可用点数里真扣（余额不足要失败的那些）。 */
    public boolean consumesAvailable() {
        return CONSUMING.contains(this);
    }

    public static PointTxnType of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
