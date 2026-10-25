package com.wherelee.cabinet.funds.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.funds.domain.BizPayTxn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface BizPayTxnMapper extends BaseMapper<BizPayTxn> {

    @Update("""
            update biz_pay_txn
               set status = 'PAID', trade_no = #{tradeNo}, paid_at = #{paidAt}, update_time = now(3)
             where id = #{id} and status = 'CREATED' and deleted = 0
            """)
    int markPaid(@Param("id") Long id, @Param("tradeNo") String tradeNo, @Param("paidAt") LocalDateTime paidAt);

    @Update("""
            update biz_pay_txn
               set status = 'FAILED', update_time = now(3)
             where id = #{id} and status = 'CREATED' and deleted = 0
            """)
    int markFailed(@Param("id") Long id);
}
