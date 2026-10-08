package com.wherelee.cabinet.domain.enums;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 格口尺寸。
 *
 * <p>{@code rank} 用来做“最小满足尺寸”分配：行李箱来了不能塞进小格口，
 * 而中格口 available 时不该把大格口花掉（大格口少，留给真需要的件）。
 * rank 越小越“贵”（越要省着用），所以分配时从 {@code 需求尺寸开始向上找第一个空闲}。
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
     * 分配偏好顺序：从“刚好够用”开始往大了排。
     *
     * <p>大格口是全柜最少的一种（模板里 6/8/10），把行李箱以外的需求都优先分到
     * 小格口，等价于给稀缺资源做“不换尺寸”的贪心。不这么排会出现：
     * 存一个背包用掉一个大格口，真正带行李箱的人拿不到位。
     */
    public static List<SizeType> acceptanceOrder(SizeType required) {
        return Arrays.stream(values())
                .filter(s -> s.fits(required))
                .sorted(Comparator.comparingInt((SizeType s) -> s.rank).reversed())
                .toList();
    }

    public static SizeType of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
