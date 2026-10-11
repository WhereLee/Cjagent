package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizWorkOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 工单读写（19B）。
 *
 * <p>认领、处理、自动转化全部写成<b>带状态条件的 UPDATE</b>，靠影响行数判胜负：
 * “先查再改”在两个业务员同时点认领时会双赢（都读到 PENDING，都写回自己）。
 * 这与点数扣减、格口占用是同一个手法，不是多余的谨慎。
 */
@Mapper
public interface BizWorkOrderMapper extends BaseMapper<BizWorkOrder> {

    /**
     * 认领：只有还挂在 PENDING 的单能被认领，一个抢到另一个必然影响 0 行。
     * 已经被自己认领的不算失败也不重复改（重放友好）。
     */
    @Update("""
            update biz_work_order
               set status = 'CLAIMED', assignee_id = #{operatorId}, claimed_at = NOW(3),
                   version = version + 1, update_time = NOW(3)
             where id = #{id} and deleted = 0 and status = 'PENDING'
            """)
    int claim(@Param("id") Long id, @Param("operatorId") Long operatorId);

    /**
     * 人工处理完：状态落定同时把 active_flag 置 NULL——不置空就占着唯一索引槽位，
     * 这一格下次再出同类异常会开不出新单（异常被历史堵住，比刷重更糟）。
     */
    @Update("""
            update biz_work_order
               set status = 'RESOLVED', active_flag = null, result_note = #{note},
                   closed_at = NOW(3), version = version + 1, update_time = NOW(3)
             where id = #{id} and deleted = 0 and active_flag = 1
            """)
    int resolve(@Param("id") Long id, @Param("note") String note);

    /** 事实自行消失（门关上了）：自动转化留底，绝不删除，也不写成人做的结论。 */
    @Update("""
            update biz_work_order
               set status = 'AUTO_CLOSED', active_flag = null, closed_at = NOW(3),
                   result_note = concat(coalesce(result_note, ''), '[自动转化：异常事实已消失]'),
                   version = version + 1, update_time = NOW(3)
             where slot_id = #{slotId} and type = #{type} and deleted = 0 and active_flag = 1
            """)
    int autoCloseBySlot(@Param("slotId") Long slotId, @Param("type") String type);

    /**
     * 升级到紧急并拉紧处理时限（阶梯到“业务员必须处理”那一档时调）。
     *
     * <p>条件里带 `active_flag = 1`：已经被关（人或事实收口）的单不能被“催回来”，
     * 否则会出现“已解决的历史单又在等人处理”这种假待办。<br>
     * 另带一个“还没升级过”的条件（已紧急且时限已到 = 不再命中）：
     * 升级在阶梯里会被反复经过，不卡这个条件就会每轮刷一条电话任务与站内消息。
     */
    @Update("""
            update biz_work_order
               set urgent = 1, due_at = NOW(3),
                   version = version + 1, update_time = NOW(3)
             where slot_id = #{slotId} and type = #{type} and deleted = 0 and active_flag = 1
               and (urgent = 0 or due_at is null or due_at > NOW(3))
            """)
    int escalateBySlot(@Param("slotId") Long slotId, @Param("type") String type);
}
