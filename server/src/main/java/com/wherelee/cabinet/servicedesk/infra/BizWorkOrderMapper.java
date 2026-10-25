package com.wherelee.cabinet.servicedesk.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BizWorkOrderMapper extends BaseMapper<BizWorkOrder> {

    @Update("""
            update biz_work_order
               set status = 'CLAIMED', assignee_id = #{operatorId}, claimed_at = NOW(3),
                   version = version + 1, update_time = NOW(3)
             where id = #{id} and deleted = 0 and status = 'PENDING'
            """)
    int claim(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("""
            update biz_work_order
               set status = 'RESOLVED', active_flag = null, result_note = #{note},
                   close_reason = #{closeReason}, closed_at = NOW(3),
                   version = version + 1, update_time = NOW(3)
             where id = #{id} and deleted = 0 and active_flag = 1
            """)
    int resolve(@Param("id") Long id, @Param("note") String note,
                @Param("closeReason") String closeReason);

    @Update("""
            update biz_work_order
               set status = 'RESOLVED', active_flag = null, close_reason = 'USER_CONFIRMED',
                   result_note = concat(coalesce(result_note, ''), #{note}),
                   closed_at = NOW(3), version = version + 1, update_time = NOW(3)
             where id = #{id} and customer_id = #{customerId} and deleted = 0 and active_flag = 1
            """)
    int confirmResolved(@Param("id") Long id, @Param("customerId") Long customerId,
                        @Param("note") String note);

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
