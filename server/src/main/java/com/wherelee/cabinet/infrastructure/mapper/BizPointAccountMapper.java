package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizPointAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 点数账户读写。
 *
 * <p><b>余额变更一律用下面的条件更新，不允许"先 select 再 set 回写"。</b>
 * 先查后改在并发下必然超扣（两个线程都读到 100，各扣 80，余额变 -60）。
 * 正确形态是让数据库自己判：{@code points + delta >= 0} 作为 WHERE 条件，
 * <b>靠影响行数 0 判定"余额不足"</b>。两道防线都要有：
 * <ul>
 *   <li>条件 UPDATE：并发正确性（第一道，也是唯一能防抖的一道）；</li>
 *   <li>DDL 上的 {@code CHECK (points >= 0 AND frozen_points >= 0)}：兜住代码里的手误
 *       （比如有人新写一条 update 忘了条件）。只靠 CHECK 会变成异常风暴，
 *       只靠条件 UPDATE 则挡不住下一个改代码的人。</li>
 * </ul>
 *
 * <p>{@code version} 在这里<b>故意不参与 WHERE</b>：条件是数值比较而不是版本号，
 * 让同一账户上的不同操作（不同订单的冻结）能并行推进；写冲突由条件本身挡下。
 * 但版本号仍要 +1——它是"余额被改过"的凭证，超时回扫与对账要靠它判断读到的快照是否还有效。
 */
@Mapper
public interface BizPointAccountMapper extends BaseMapper<BizPointAccount> {

    /**
     * 变更可用点数。delta 带符号；不足时影响 0 行。
     */
    @Update("""
            update biz_point_account
               set points = points + #{delta}, version = version + 1, update_time = NOW(3)
             where id = #{id} and points + #{delta} >= 0 and deleted = 0
            """)
    int changeAvailable(@Param("id") Long id, @Param("delta") long delta);

    /**
     * 变更冻结点数。delta 带符号；要把冻结扣成负数时影响 0 行
     * （典型场景：同一笔冻结被重复解冻，必须失败而不是把别的冻结吃掉）。
     */
    @Update("""
            update biz_point_account
               set frozen_points = frozen_points + #{delta}, version = version + 1, update_time = NOW(3)
             where id = #{id} and frozen_points + #{delta} >= 0 and deleted = 0
            """)
    int changeFrozen(@Param("id") Long id, @Param("delta") long delta);

    /**
     * 变更账户级押金栅。同样靠条件更新：“把押金扣成负数”时影响 0 行
     * （典型场景：两个退押金请求并到同一账户，只可能有一个成立）。
     */
    @Update("""
            update biz_point_account
               set deposit_points = deposit_points + #{delta}, version = version + 1, update_time = NOW(3)
             where id = #{id} and deposit_points + #{delta} >= 0 and deleted = 0
            """)
    int changeDeposit(@Param("id") Long id, @Param("delta") long delta);
}
