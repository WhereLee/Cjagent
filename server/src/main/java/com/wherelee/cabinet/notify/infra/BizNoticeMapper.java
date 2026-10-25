package com.wherelee.cabinet.notify.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.notify.domain.BizNotice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BizNoticeMapper extends BaseMapper<BizNotice> {

    @Update("""
            update biz_notice
               set done_at = NOW(3), done_note = #{note}, update_time = NOW(3)
             where id = #{id} and deleted = 0 and done_at is null
            """)
    int markDone(@Param("id") Long id, @Param("note") String note);

    @Update("""
            update biz_notice
               set done_at = NOW(3), update_time = NOW(3)
             where id = #{id} and customer_id = #{customerId} and deleted = 0 and done_at is null
            """)
    int markReadForCustomer(@Param("id") Long id, @Param("customerId") Long customerId);
}
