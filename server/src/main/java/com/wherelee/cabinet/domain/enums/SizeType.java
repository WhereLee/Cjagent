package com.wherelee.cabinet.domain.enums;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 格口尺寸。
 *
 * <p>{@code rank} 表示“越小的格子越要省着用”（LARGE=1 最稀缺，SMALL=3 最便宜）。
 * 自 2026-10-10 起分配**不再向上升级**（见 {@link #acceptanceOrder}），所以
 * {@link #fits} 目前只用于“这个格子能不能装下这个尺寸”的校验，不再用于择优顺序。
 */
public enum SizeType {

    LARGE(1),
    MEDIUM(2),
    SMALL(3);

    private final int rank;

    SizeType(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** 本尺寸能否容纳 target 尺寸的需求：大能装中的需求，小不能装大的需求。 */
    public boolean fits(SizeType required) {
        return this.rank <= required.rank;
    }

    /**
     * 分配偏好：**只接受用户所选的那一个尺寸，不往上升**（2026-10-10 用户定）。
     *
     * <p>以前的做法是“小格满了就自动给中格、按中格收钱”，它跟“先给用户展示各尺寸还剩多少”
     * 直接矛盾：他看到小格还有 1 个，抢到的却是中格的价格。用户定的口径是：
     * 10 个人抢最后一个小格，1 人成、其他 9 人收到“无位”即可；要大的他自己选大的。
     *
     * <p>保留这个函数而不是改调用方：四个分配器 + 预扣 + 校准共用一个入口，
     * 收严一处就全线收严（回到以前行为只需改回这一行）。
     */
    public static List<SizeType> acceptanceOrder(SizeType required) {
        return List.of(required);
    }

    public static SizeType of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
