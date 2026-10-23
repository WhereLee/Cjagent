package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizOutbox;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * outbox 的入队、抢占与投递结果。
 *
 * <p><b>这些语句全部 {@code @InterceptorIgnore} 跳过租户注入</b>：中继是跨租户的基础设施，
 * 被过滤成"只看本租户"的话，其他租户的待投递永远发不出去，而且不报错——只是不动。
 * 投递时仍会 {@code TenantContext.runAs(row.tenantId)}，业务副作用照旧受隔离。
 * 与 {@link BizDelayTaskMapper} 同一个理由，那条"调度线程没有租户上下文，用 selectById 会被守卫拒执"
 * 的坑已经在那边实测过。
 *
 * <p>入队与推进都写成条件更新/upsert，靠影响行数表达"我抢到了"：<b>不依赖任何"先查后改"</b>。
 */
@Mapper
public interface BizOutboxMapper extends BaseMapper<BizOutbox> {

    /**
     * 入队。{@code uk_outbox_fact} 是"同一事实只入队一次"的裁判。
     *
     * <p>冲突时<b>什么都不做</b>（不刷新 next_attempt_at、不重置状态）：
     * 一条已经投递成功的事实被重复登记，不该再发一遍；而一条已判死的事实，
     * 静默复活会掩盖真问题（与延迟任务"DEAD 保持不动"同一个理由）。
     *
     * <p><b>必须标 {@code @Insert} 而不是 {@code @Update}</b>：语句是 upsert，看着像更新，
     * 但 MyBatis 的注解决定 {@code SqlCommandType}，而 {@code BlockAttackInnerInterceptor}
     * 只处理 UPDATE/DELETE——标成 {@code @Update} 它会去解析这条 SQL，撞到 INSERT 节点抛
     * 无信息的 {@code UnsupportedOperationException}，表现为"入口必炸却查不到原因"。
     * {@code BizDelayTaskMapper.register} 为此踩过一次，注释里记着"注解要说谎，拦截器就会在说谎处炸给你看"。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            insert into biz_outbox
                (id, tenant_id, event_type, dedup_key, payload, status, attempt, next_attempt_at,
                 create_time, update_time, deleted)
            values (#{id}, #{tenantId}, #{eventType}, #{dedupKey}, #{payload}, 'PENDING', 0, now(3),
                    now(3), now(3), 0)
            on duplicate key update status = status
            """)
    int append(@Param("id") Long id,
               @Param("tenantId") Long tenantId,
               @Param("eventType") String eventType,
               @Param("dedupKey") String dedupKey,
               @Param("payload") String payload);

    /**
     * 取一批到期待投的行（跨租户）。
     *
     * <p>不加 {@code for update skip locked}：互斥由 {@link #claim} 的条件更新裁判。
     * 多个实例同时拿到同一批 id 也没关系，只有一个能在 claim 里拿到 1 行。
     *
     * <p>FAILED 必须在这里重新露面：退避后写的就是 FAILED，
     * 若候选只认 PENDING，重试永远执行不到，而且不报错——外表就是"还在重试"的事实躺到地老天荒。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id from biz_outbox
             where deleted = 0 and status in ('PENDING','FAILED') and next_attempt_at <= now(3)
             order by next_attempt_at
             limit #{limit}
            """)
    List<Long> findDueIds(@Param("limit") int limit);

    /**
     * 抢占：把 next_attempt_at 推远并 attempt+1。
     *
     * <p>WHERE 里重复了"可投"条件——这是最后一道防线：即使候选集判断与这次更新之间
     * 已被别的实例抢走（时间已推远），这里也只有一个人拿到 1 行。
     *
     * <p>推远的时长用退避基数：抢到之后若进程被杀，这条事实会在一个基数周期后重新露面，
     * 不会永久卡住。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_outbox
               set next_attempt_at = #{nextAttemptAt}, attempt = attempt + 1, update_time = now(3)
             where id = #{id} and deleted = 0
               and status in ('PENDING','FAILED') and next_attempt_at <= now(3)
            """)
    int claim(@Param("id") Long id, @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    /** 投递成功。只从 PENDING/FAILED 转，避免把一条已判死的行改成 DONE 掩盖问题。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_outbox
               set status = 'DONE', done_at = now(3), last_error = null, update_time = now(3)
             where id = #{id} and deleted = 0 and status in ('PENDING','FAILED')
            """)
    int markDone(@Param("id") Long id);

    /** 投递失败：还在重试预算内就写 FAILED + 退避时间，用尽则 DEAD。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_outbox
               set status = #{status}, next_attempt_at = #{nextAttemptAt}, last_error = #{error},
                   update_time = now(3)
             where id = #{id} and deleted = 0 and status in ('PENDING','FAILED')
            """)
    int markOutcome(@Param("id") Long id,
                    @Param("status") String status,
                    @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                    @Param("error") String error);

    /**
     * 按 id 取整行（<b>跨租户，中继专用</b>）。
     *
     * <p>在 {@code claim} 之后读，所以 {@code attempt} 已经包含本轮——
     * 判死用的是这个数，不是读之前的旧值。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id, tenant_id, event_type, dedup_key, payload, status, attempt, next_attempt_at,
                   last_error, done_at, create_time, update_time, deleted
            from biz_outbox
            where id = #{id} and deleted = 0
            """)
    BizOutbox selectForWorker(@Param("id") Long id);
}
