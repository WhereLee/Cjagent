package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
     */
    @Update("update biz_compartment set status = 'RESERVED', current_order_id = #{orderId}, "
            + "version = version + 1, update_time = NOW(3) "
            + "where id = #{slotId} and status = 'FREE' and deleted = 0")
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
}
