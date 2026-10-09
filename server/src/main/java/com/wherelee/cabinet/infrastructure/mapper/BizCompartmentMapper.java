package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.enums.CompartmentAnomaly;
import com.wherelee.cabinet.domain.enums.Presence;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface BizCompartmentMapper extends BaseMapper<BizCompartment> {

    /**
     * 抢占式占用（CAS）：只有从 FREE 变 RESERVED 才生效，靠**影响行数**判定抢到没抢到。
     *
     * <p>为什么写显式 SQL 而不是 {@code updateById}：
     * ① {@code WHERE status='FREE'} 这个条件本身就是原子性的一部分，updateById 只能按主键更新；
     * ② MP 默认不写 null 字段（见 docs/架构约定.md §3.5），释放时的
     * {@code current_order_id = NULL} 必须能真写进去。
     * {@code update_time} 在这里手动给，不依赖 MetaObjectHandler。
     *
     * <p><b>守卫条件必须与 {@code SlotCandidateQuery.assignable()} 同判据</b>：
     * 候选查询负责“挑”，这里的 where 负责“原子地确认”。两边不一致时拦下的就不再是
     * 并发竞争而是正常业务，用户会莫名“抢输”，而且拿到过开门占走/有遗留物的格子。
     */
    @Update("update biz_compartment set status = 'RESERVED', current_order_id = #{orderId}, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and status = 'FREE' and door_open_at is null and anomaly is null "
            + "and deleted = 0")
    int reserveSlot(@Param("slotId") Long slotId, @Param("orderId") Long orderId);

    /**
     * 释放（取消/超时回滚）：条件里带上 current_order_id，
     * 保证“只能释放自己那张单占的位”——否则一张已取消的旧单会把别人的新单踢下线。
     */
    @Update("update biz_compartment set status = 'FREE', current_order_id = null, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and current_order_id = #{orderId} and status = 'RESERVED' and deleted = 0")
    int releaseSlot(@Param("slotId") Long slotId, @Param("orderId") Long orderId);

    /**
     * 取件后释放（OCCUPIED → FREE）。与 {@link #releaseSlot} 分开而不是写成
     * {@code status in ('RESERVED','OCCUPIED')}：这两个状态对应两件不同的事
     * （未投件取消 vs 已投件取走），混在一条里就丢掉了“件在不在柜里”这个关键信息，
     * 一旦出事故无法从数据上判断发生过什么。
     */
    @Update("update biz_compartment set status = 'FREE', current_order_id = null, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and current_order_id = #{orderId} and status = 'OCCUPIED' and deleted = 0")
    int releaseOccupiedSlot(@Param("slotId") Long slotId, @Param("orderId") Long orderId);

    /** 投件完成（关门校验通过）：RESERVED → OCCUPIED，只有自己的单能推自己的格口。 */
    @Update("update biz_compartment set status = 'OCCUPIED', version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and current_order_id = #{orderId} and status = 'RESERVED' and deleted = 0")
    int markOccupied(@Param("slotId") Long slotId, @Param("orderId") Long orderId);

    // ------------------------------------------------------------------
    // 门态与物检（docs/门态与物品争议设计.md）
    //
    // 这些方法都写成**条件更新**而不是“反正盖写一次”：它们记录的是物理事实的起点，
    // 而起点一旦飘移就会直接改掉钱。MP 默认不写 null，所以“把 open 起点清空”这类动作
    // 只能走显式 SQL（第 12 刀的实测坑）。
    // ------------------------------------------------------------------

    /**
     * 记“门开了”：只有当前是关着的才能记。
     *
     * <p>为什么带 {@code door_open_at is null}：重复回执/重试若把起点改写，
     * 一个已经开了 30 分钟的门会被“刷成刚开”，容错计时与计费起点全跟着错。
     */
    @Update("update biz_compartment set door_open_at = #{at}, door_opened_by = #{customerId}, "
            + "door_closed_at = null, door_closed_by = null, version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and door_open_at is null and deleted = 0")
    int markDoorOpen(@Param("slotId") Long slotId, @Param("customerId") Long customerId,
                     @Param("at") LocalDateTime at);

    /**
     * 记“门关了”：只有开着的门才关得掉；清掉 open 起点并记下 closed 与关的人。
     *
     * <p>{@code closed_at}/{@code closed_by} 必须记：代关（下一个用户顺手关门）出纠纷时，
     * “谁在什么时候关的”是唯一的还原依据。
     */
    @Update("update biz_compartment set door_open_at = null, door_closed_at = #{at}, door_closed_by = #{customerId}, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and door_open_at is not null and deleted = 0")
    int markDoorClosed(@Param("slotId") Long slotId, @Param("customerId") Long customerId,
                       @Param("at") LocalDateTime at);

    /** 落一次柜内物检结论（带时刻；结论会不会过期由调用方按 presenceStillValid 判）。 */
    @Update("update biz_compartment set presence = #{presence}, presence_checked_at = #{at}, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and deleted = 0")
    int recordPresence(@Param("slotId") Long slotId, @Param("presence") Presence presence,
                       @Param("at") LocalDateTime at);

    /**
     * 标异常。允许覆盖（异常会升级：门未关 → 传感器矛盾），但必须留新的判定时刻与说明。
     *
     * <p>注意这里<b>不改 status</b>：异常与“能不能卖”是两件事，可分配判定在
     * {@code SlotCandidateQuery} 里同时看两个字段。在这一点上合并它们，
     * 就会丢掉“硬件故障”与“里面有东西”的区别。
     */
    @Update("update biz_compartment set anomaly = #{anomaly}, anomaly_at = #{at}, anomaly_reason = #{reason}, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and deleted = 0")
    int markAnomaly(@Param("slotId") Long slotId, @Param("anomaly") CompartmentAnomaly anomaly,
                    @Param("at") LocalDateTime at, @Param("reason") String reason);

    /**
     * 清异常：只允许“来路正当”的调用者清（门关且无物的自动解除、或业务运维人工确认）。
     *
     * <p>写成条件更新（{@code anomaly is not null}）是为了让“重复解除”影响 0 行而不是静默成功；
     * 调用方必须看影响行数，返回 0 时应该去读一下现在到底是个什么异常。
     */
    @Update("update biz_compartment set anomaly = null, anomaly_at = null, anomaly_reason = null, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and anomaly is not null and deleted = 0")
    int clearAnomaly(@Param("slotId") Long slotId);
}
