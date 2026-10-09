package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizPayTxn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 通道流水读写。
 *
 * <p>状态推进只给条件更新：<b>只有 CREATED 能变 PAID</b>。重复回调、"查单 + 回调"同时到达
 * 都是真实场景，用 {@code updateById} 会把已经入账的一单改回未付（或反过来造出第二次入账的机会）。
 */
@Mapper
public interface BizPayTxnMapper extends BaseMapper<BizPayTxn> {

    /** @return 1 表示本次把单推到已收款；0 表示已被别的路径推过（幂等分支，不是失败） */
    @Update("""
            update biz_pay_txn
               set status = 'PAID', trade_no = #{tradeNo}, paid_at = #{paidAt}, update_time = now(3)
             where id = #{id} and status = 'CREATED' and deleted = 0
            """)
    int markPaid(@Param("id") Long id, @Param("tradeNo") String tradeNo, @Param("paidAt") LocalDateTime paidAt);

    /** 通道明确失败：同样只允许从 CREATED 走，已收款的单绝不许被改成失败。 */
    @Update("""
            update biz_pay_txn
               set status = 'FAILED', update_time = now(3)
             where id = #{id} and status = 'CREATED' and deleted = 0
            """)
    int markFailed(@Param("id") Long id);
}
