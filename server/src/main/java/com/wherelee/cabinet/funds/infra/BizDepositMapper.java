package com.wherelee.cabinet.funds.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.funds.domain.BizDeposit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface BizDepositMapper extends BaseMapper<BizDeposit> {

    @Update("""
            update biz_deposit
               set status = 'REFUNDED', refund_txn_id = #{refundTxnId}, refunded_at = #{at},
                   fail_reason = null, update_time = NOW(3)
             where id = #{id} and status = 'HELD' and deleted = 0
            """)
    int markRefunded(@Param("id") Long id, @Param("refundTxnId") Long refundTxnId, @Param("at") LocalDateTime at);

    @Update("""
            update biz_deposit
               set status = 'FAILED', fail_reason = #{reason}, update_time = NOW(3)
             where id = #{id} and status = 'HELD' and deleted = 0
            """)
    int markFailed(@Param("id") Long id, @Param("reason") String reason);
}
