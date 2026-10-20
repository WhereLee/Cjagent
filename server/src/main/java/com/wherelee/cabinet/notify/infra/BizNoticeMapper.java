package com.wherelee.cabinet.notify.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.notify.domain.BizNotice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 通知读写（19D）。
 *
 * <p>"标记已办"同样必须是条件更新（`done_at is null`）：两个业务员同时回填一通电话的结果，
 * 只该有第一条生效——否则第二个人的记录会悄悄盖掉第一个人的，而那通电话可能根本没人打通。
 */
@Mapper
public interface BizNoticeMapper extends BaseMapper<BizNotice> {

    @Update("""
            update biz_notice
               set done_at = NOW(3), done_note = #{note}, update_time = NOW(3)
             where id = #{id} and deleted = 0 and done_at is null
            """)
    int markDone(@Param("id") Long id, @Param("note") String note);

    /** 站内消息的已读只认"这条消息属于这个客户"：不然谁能替别人读消息。 */
    @Update("""
            update biz_notice
               set done_at = NOW(3), update_time = NOW(3)
             where id = #{id} and customer_id = #{customerId} and deleted = 0 and done_at is null
            """)
    int markReadForCustomer(@Param("id") Long id, @Param("customerId") Long customerId);
}
