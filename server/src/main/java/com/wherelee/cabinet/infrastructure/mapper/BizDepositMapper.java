package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizDeposit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 押金读写。状态推进用条件更新，<b>{@code where status = 'HELD'} 是关键</b>：
 * 押金退款的重复触发（用户连点、调度重试、人工补退）如果只靠"读出来判断再写回"，
 * 两个并发者会都看到 HELD 然后各退一次——退两次押金是真金白银损失，
 * 比超卖更贵，所以这里同样是"数据库自己判"。
 */
@Mapper
public interface BizDepositMapper extends BaseMapper<BizDeposit> {

    /** HELD → REFUNDED：影响 0 行说明已经被别人退掉了（幂等退出，不报错）。 */
    @Update("""
            update biz_deposit
               set status = 'REFUNDED', refund_txn_id = #{refundTxnId}, refunded_at = #{at},
                   fail_reason = null, update_time = NOW(3)
             where id = #{id} and status = 'HELD' and deleted = 0
            """)
    int markRefunded(@Param("id") Long id, @Param("refundTxnId") Long refundTxnId, @Param("at") LocalDateTime at);

    /** HELD → FAILED：留下原因，供"未退清单"（idx_deposit_status_time）扫描重试。 */
    @Update("""
            update biz_deposit
               set status = 'FAILED', fail_reason = #{reason}, update_time = NOW(3)
             where id = #{id} and status = 'HELD' and deleted = 0
            """)
    int markFailed(@Param("id") Long id, @Param("reason") String reason);
}
