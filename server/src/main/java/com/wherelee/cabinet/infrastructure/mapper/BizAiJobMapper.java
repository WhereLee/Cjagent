package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizAiJob;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 作业的入队、认领与结果回写。
 *
 * <p><b>执行器相关的语句全部 {@code @InterceptorIgnore} 跳过租户注入</b>（worker 跨租户认领），
 * 而<b>前端读作业不走这里</b>——那条路径用 {@code BaseMapper}，租户隔离照常生效。
 * 与 {@link BizDelayTaskMapper} 同一条分工：跳过注入只决定“该跑哪一条”，
 * 业务副作用仍然在 {@code TenantContext.runAs} 里执行。
 *
 * <p>入队、认领、回写全部是条件更新/upsert，靠影响行数表达“我抢到了”，不依赖“先查后改”。
 */
@Mapper
public interface BizAiJobMapper extends BaseMapper<BizAiJob> {

    /**
     * 入队。{@code uk_ai_job_active} 是“同一请求未完成时只能有一条”的裁判。
     *
     * <p>冲突时什么都不做（不刷新状态、不换 id），由调用方用 {@link #findActiveId} 取回已存在那条——
     * 双击落到同一个作业号，是这里想要的结果，不是错误。
     *
     * <p><b>不能写成 {@code on duplicate key update id = id}</b>：实测那样冲突时会把**主键改成新插入的
     * id**（返回 2），于是调用方拿到的 id 与表里那一行对不上——测试里表现为“两次返回不同作业号但
     * 只有一行”。写成 {@code active_flag = active_flag} 才是真的一动不动（两边都是 1，无变化，返回 0）。
     *
     * <p>标 {@code @Insert} 而不是 {@code @Update}：语句是 upsert，
     * 标错会让 {@code BlockAttackInnerInterceptor} 去解析它并在 INSERT 节点抛无信息的异常
     * （{@code BizDelayTaskMapper.register} 与 {@code BizOutboxMapper.append} 都记过这条）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            insert into biz_ai_job
                (id, tenant_id, job_type, request_key, requester_id, payload, status, active_flag,
                 attempt, create_time, update_time, deleted)
            values (#{id}, #{tenantId}, #{jobType}, #{requestKey}, #{requesterId}, #{payload},
                    'SUBMITTED', 1, 0, now(3), now(3), 0)
            on duplicate key update active_flag = active_flag
            """)
    int append(@Param("id") Long id,
               @Param("tenantId") Long tenantId,
               @Param("jobType") String jobType,
               @Param("requestKey") String requestKey,
               @Param("requesterId") Long requesterId,
               @Param("payload") String payload);

    /**
     * “同一请求还有没有未完成的作业”。有就复用，不新建——这是防双击的全部实现。
     *
     * <p>不需要时间窗口：作业本身就是“正在进行”的标记。跑完（active_flag 置 null）之后同内容
     * 可以再来一次，那通常是他真想再问，拦住反而是错的。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id from biz_ai_job
             where deleted = 0 and tenant_id = #{tenantId} and job_type = #{jobType}
               and request_key = #{requestKey} and requester_id = #{requesterId} and active_flag = 1
             limit 1
            """)
    Long findActiveId(@Param("tenantId") Long tenantId,
                      @Param("jobType") String jobType,
                      @Param("requestKey") String requestKey,
                      @Param("requesterId") Long requesterId);

    /**
     * 可认领的两种：{@code SUBMITTED} 待开工；{@code RUNNING} 但租约已过期（持有者进程死了）。
     *
     * <p>没有按租户公平轮转：B 端作业是人工点按钮触发的，量级与订单完全不是一个数量级，
     * 加公平分发只会多一处要维护的东西（延迟任务那边做公平，是因为共享测试库上一个租户
     * 能把整批额度吃光，那是实测出来的问题）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id from biz_ai_job
             where deleted = 0 and active_flag = 1
               and (status = 'SUBMITTED' or (status = 'RUNNING' and lease_expire_at < now(3)))
             order by create_time
             limit #{limit}
            """)
    List<Long> findDueIds(@Param("limit") int limit);

    /** 认领：写持有者与租约，attempt +1。WHERE 里重复可运行条件，是并发下的最后一道防线。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_ai_job
               set status = 'RUNNING', lease_owner = #{owner}, lease_expire_at = #{leaseUntil},
                   attempt = attempt + 1, update_time = now(3)
             where id = #{id} and deleted = 0
               and (status = 'SUBMITTED' or (status = 'RUNNING' and lease_expire_at < now(3)))
            """)
    int claim(@Param("id") Long id, @Param("owner") String owner, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 成功：写产出、清错误、<b>释放 active_flag</b>（同内容可以再来一次）、清租约。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_ai_job
               set status = 'DONE', result = #{result}, last_error = null, active_flag = null,
                   lease_owner = null, lease_expire_at = null, update_time = now(3)
             where id = #{id} and lease_owner = #{owner} and status = 'RUNNING' and deleted = 0
            """)
    int markDone(@Param("id") Long id, @Param("owner") String owner, @Param("result") String result);

    /** 还有重试预算：回到 SUBMITTED 等下一轮。active_flag 保持 1——防双击在重试期间仍然成立。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_ai_job
               set status = 'SUBMITTED', last_error = #{error},
                   lease_owner = null, lease_expire_at = null, update_time = now(3)
             where id = #{id} and lease_owner = #{owner} and status = 'RUNNING' and deleted = 0
            """)
    int markRetry(@Param("id") Long id, @Param("owner") String owner, @Param("error") String error);

    /** 次数用尽：FAILED 并释放 active_flag——用户可以再点一次，那算一次新的作业。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_ai_job
               set status = 'FAILED', last_error = #{error}, active_flag = null,
                   lease_owner = null, lease_expire_at = null, update_time = now(3)
             where id = #{id} and lease_owner = #{owner} and status = 'RUNNING' and deleted = 0
            """)
    int markFailed(@Param("id") Long id, @Param("owner") String owner, @Param("error") String error);

    /**
     * 跨租户读整行（执行器专用）。
     *
     * <p>在 {@code claim} 之后读，所以 {@code attempt} 已经包含本轮——判次数用这个值，不是读之前的旧值。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id, tenant_id, job_type, request_key, requester_id, payload, status, active_flag,
                   result, last_error, attempt, lease_owner, lease_expire_at,
                   create_time, update_time, deleted
            from biz_ai_job where id = #{id} and deleted = 0
            """)
    BizAiJob selectForWorker(@Param("id") Long id);
}
