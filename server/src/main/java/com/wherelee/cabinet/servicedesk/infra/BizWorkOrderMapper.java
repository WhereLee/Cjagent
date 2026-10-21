package com.wherelee.cabinet.servicedesk.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
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
                   close_reason = #{closeReason}, closed_at = NOW(3),
                   version = version + 1, update_time = NOW(3)
             where id = #{id} and deleted = 0 and active_flag = 1
            """)
    int resolve(@Param("id") Long id, @Param("note") String note,
                @Param("closeReason") String closeReason);

    /**
     * 用户在小程序确认“问题已解决”：
     * 关闭依据是用户本人的确认，不是业务员的结论，所以 close_reason 必须是 USER_CONFIRMED。
     */
    @Update("""
            update biz_work_order
               set status = 'RESOLVED', active_flag = null, close_reason = 'USER_CONFIRMED',
                   result_note = concat(coalesce(result_note, ''), #{note}),
                   closed_at = NOW(3), version = version + 1, update_time = NOW(3)
             where id = #{id} and customer_id = #{customerId} and deleted = 0 and active_flag = 1
            """)
    int confirmResolved(@Param("id") Long id, @Param("customerId") Long customerId,
                        @Param("note") String note);

    /**
     * 用户反馈“还没解决”：把已关闭的本人单重新打开，reopened_count +1。
     * 只允许 RESOLVED/AUTO_CLOSED，且必须还是同一客户——不给他人的历史单开旁路。
     */
    @Update("""
            update biz_work_order
               set status = 'PENDING', active_flag = 1, close_reason = null, closed_at = null,
                   result_note = concat(coalesce(result_note, ''), #{note}),
                   reopened_count = reopened_count + 1, version = version + 1, update_time = NOW(3)
             where id = #{id} and customer_id = #{customerId} and deleted = 0
               and active_flag is null and status in ('RESOLVED','AUTO_CLOSED')
            """)
    int reopen(@Param("id") Long id, @Param("customerId") Long customerId,
               @Param("note") String note);

    /** 重开时同格口已有同类未关闭单：把当前单并入那张，记 MERGED，不再占唯一索引。 */
    @Update("""
            update biz_work_order
               set status = 'RESOLVED', close_reason = 'MERGED', merged_into_id = #{targetId},
                   active_flag = null, closed_at = NOW(3),
                   result_note = concat(coalesce(result_note, ''), #{note}),
                   version = version + 1, update_time = NOW(3)
             where id = #{id} and deleted = 0 and active_flag is null
            """)
    int markMerged(@Param("id") Long id, @Param("targetId") Long targetId,
                   @Param("note") String note);

    /** 事实自行消失（门关上了）：自动转化留底，绝不删除，也不写成人做的结论。 */
    @Update("""
            update biz_work_order
               set status = 'AUTO_CLOSED', active_flag = null, closed_at = NOW(3),
                   close_reason = 'FACT_RECOVERED',
                   result_note = concat(coalesce(result_note, ''), '[自动转化：异常事实已消失]'),
                   version = version + 1, update_time = NOW(3)
             where slot_id = #{slotId} and type = #{type} and kind = 'INCIDENT'
               and deleted = 0 and active_flag = 1
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
               set urgent = 1, priority = 'P1', due_at = NOW(3),
                   version = version + 1, update_time = NOW(3)
             where slot_id = #{slotId} and type = #{type} and kind = 'INCIDENT'
               and deleted = 0 and active_flag = 1
               and (urgent = 0 or due_at is null or due_at > NOW(3))
            """)
    int escalateBySlot(@Param("slotId") Long slotId, @Param("type") String type);
}
