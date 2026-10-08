package com.wherelee.cabinet.domain.enums;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 寄存单状态机。
 *
 * <p>三件事集中在这里，不允许散在业务代码里：
 * ① 合法迁移（{@link #canTransitTo}）；② 哪些是终态（{@link #isTerminal}）；
 * ③ <b>活动标记</b>（{@link #activeFlag}）——它决定数据库唯一索引
 * {@code uk_order_active_slot} 能不能拦住超卖。
 *
 * <p>特别注意 {@link #ABNORMAL} 与 {@link #EXPIRED} <b>不是终态</b>：
 * 异常单必须还有出口（人工核验放行或撤销），逾期单也还得能取件。
 * 把它们当终态是这类系统最常见的"东西永久锁在柜子里"的原因
 * ——每个异常状态都要能回答"然后怎么走出去"。
 */
public enum OrderStatus {

    /** 已下单，还没分配格口 */
    INIT,
    /** 格口已预占（带 TTL），等待客户确认投件 */
    RESERVED,
    /** 开柜指令已下发，尚未收到"门已开"回执 */
    OPENING,
    /** 门已开、件已放入、门已关，等待开始计费 */
    STORED,
    /** 计费中（正常寄存期） */
    ACTIVE,
    /** 临时开柜中（中途取物），此时格口不可被回收也不可被再次分配 */
    TEMP_OPEN,
    /** 取件结算中（按实际时长核销、差额补扣或退还） */
    SETTLING,
    /** 正常结束：件已取走、押金进入退还流程 */
    CLOSED,
    /** 取消：未开始计费即撤销（免费取消窗口内、或超时未关门自动撤销） */
    CANCELLED,
    /** 异常：谎报、部分成功、状态不一致等，等人工或补偿任务处置 */
    ABNORMAL,
    /** 逾期未取（已封顶计费），仍可取件结算 */
    EXPIRED;

    private static final Set<OrderStatus> TERMINAL = EnumSet.of(CLOSED, CANCELLED);

    /**
     * 合法迁移表。写死并集中，是为了让"非法迁移"成为可被单测穷举断言的行为，
     * 而不是靠 review 时盯住有没有人乱 setStatus。
     */
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        TRANSITIONS.put(INIT, EnumSet.of(RESERVED, CANCELLED, ABNORMAL));
        TRANSITIONS.put(RESERVED, EnumSet.of(OPENING, CANCELLED, ABNORMAL));
        TRANSITIONS.put(OPENING, EnumSet.of(STORED, ACTIVE, RESERVED, CANCELLED, ABNORMAL));
        TRANSITIONS.put(STORED, EnumSet.of(ACTIVE, CANCELLED, ABNORMAL));
        TRANSITIONS.put(ACTIVE, EnumSet.of(TEMP_OPEN, SETTLING, EXPIRED, ABNORMAL));
        TRANSITIONS.put(TEMP_OPEN, EnumSet.of(ACTIVE, ABNORMAL));
        TRANSITIONS.put(SETTLING, EnumSet.of(CLOSED, ABNORMAL));
        TRANSITIONS.put(EXPIRED, EnumSet.of(SETTLING, ABNORMAL));
        TRANSITIONS.put(ABNORMAL, EnumSet.of(ACTIVE, CANCELLED, CLOSED));
        TRANSITIONS.put(CLOSED, EnumSet.noneOf(OrderStatus.class));
        TRANSITIONS.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));

        // 每个状态都要有一条 entry：漏一个就会在运行时默默“不能迁移”，比编译期报错难查
        for (OrderStatus status : values()) {
            TRANSITIONS.computeIfAbsent(status, k -> EnumSet.noneOf(OrderStatus.class));
        }
        // 终态不得有出边，防止“已关闭的单被补偿任务悄悄改回活动中”
        TERMINAL.forEach(terminal -> {
            if (!TRANSITIONS.get(terminal).isEmpty()) {
                throw new IllegalStateException("终态不允许有出边: " + terminal);
            }
        });
    }

    public boolean canTransitTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(OrderStatus.class)).contains(target);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * 写库时的 active_flag 值：活动期为 1，终态为 NULL。
     *
     * <p>唯一索引 {@code (slot_id, active_flag)} 依赖这个函数——NULL 可重复、1 不可重复，
     * 于是"同一格口两条活动单"在存储层就插不进去。<b>这一行改错，防超卖就失效了</b>。
     */
    public Integer activeFlag() {
        return isTerminal() ? null : 1;
    }
}
