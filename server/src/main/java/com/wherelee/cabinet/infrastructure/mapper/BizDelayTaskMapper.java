package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 延迟任务抢占与状态推进。
 *
 * <p><b>这些语句全部 {@code @InterceptorIgnore} 跳过租户注入</b>：worker 是跨租户的基础设施，
 * 若被过滤成"只看本租户"，其他租户的超时占位永远不会被释放（而且没人报错，只是不动）。
 * 业务副作用则相反——handler 必须在 {@code TenantContext.runAs(task.tenantId)} 里执行，
 * 让订单/格口的写入照样受租户隔离。
 *
 * <p>登记与推进都写成条件更新，靠影响行数表达"我抢到了"：<b>不依赖任何"先查后改"</b>。
 *
 * <p><b>为什么这条必须标 {@code @Insert} 而不是 {@code @Update}</b>（本刀实测踩到）：
 * 语句是 upsert，看着像"更新"，但 MyBatis 的注解决定 {@code SqlCommandType}，
 * 而 MP 的 {@code BlockAttackInnerInterceptor}（全表更新/删除防护）只处理 UPDATE/DELETE 类型——
 * 标成 {@code @Update} 它就去解析这条 SQL，解析到 INSERT 节点时
 * {@code JsqlParserSupport.processInsert} 的默认实现直接抛无信息的
 * {@code UnsupportedOperationException}，表现为"下单必炸"却查不到原因。
 * 规则：<b>注解要说谎，拦截器就会在说谎处炸给你看</b>。
 */
@Mapper
public interface BizDelayTaskMapper extends BaseMapper<BizDelayTask> {

    /**
     * 取一批到期待抢的任务。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 让并发 worker 拿到<b>不相交</b>的候选集：
     * 已被别人锁住的行直接跳过而不是排队等待，于是热点表不会把所有实例串成一条队。
     *
     * <p>可抢的三种：PENDING 且已到时间；<b>FAILED 且已到退避时间</b>；RUNNING 但<b>租约已过期</b>
     * （持有者进程死了，没有这一条，实例被 kill 会让任务永久卡死）。
     *
     * <p>FAILED 必须在这里重新露面（本刀实测）：{@code markOutcome} 失败后写的就是 FAILED，
     * 若候选条件只认 PENDING，退避重试就永远执行不到，而且不报错——
     * 外表就是一张“还在重试”的任务在库里躺到地老天荒。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id from biz_delay_task
             where deleted = 0
               and ((status in ('PENDING','FAILED') and fire_at <= now(3))
                 or (status = 'RUNNING' and lease_expire_at < now(3)))
             order by fire_at
             limit #{limit}
             for update skip locked
            """)
    List<Long> findDueIdsForUpdateSkip(@Param("limit") int limit);

    /**
     * 抢占单个任务：写入持有者与租约，attempt +1。
     *
     * <p>WHERE 里重复了"可抢"条件——这是最后一道防线：即使候选集判断与本次更新之间
     * 已被别人抢走（或租约刚续上），这里也只会有一个人拿到 1 行。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_delay_task
               set status = 'RUNNING', lease_owner = #{owner}, lease_expire_at = #{leaseUntil},
                   attempt = attempt + 1, update_time = now(3)
             where id = #{id} and deleted = 0
               and ((status in ('PENDING','FAILED') and fire_at <= now(3))
                 or (status = 'RUNNING' and lease_expire_at < now(3)))
            """)
    int claim(@Param("id") Long id, @Param("owner") String owner, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 只有持有者能标完成；owner 不匹配说明租约已被接管，本次结果不能覆盖别人的进展。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_delay_task
               set status = 'DONE', last_error = null, lease_owner = null, lease_expire_at = null,
                   update_time = now(3)
             where id = #{id} and lease_owner = #{owner} and status = 'RUNNING' and deleted = 0
            """)
    int markDone(@Param("id") Long id, @Param("owner") String owner);

    /**
     * 失败/退避：还在重试次数内就回到 PENDING 并把 fire_at 推到 {@code nextFireAt}，
     * 否则置 DEAD（终态，等人工）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_delay_task
               set status = #{status}, fire_at = #{nextFireAt}, last_error = #{error},
                   lease_owner = null, lease_expire_at = null, update_time = now(3)
             where id = #{id} and lease_owner = #{owner} and status = 'RUNNING' and deleted = 0
            """)
    int markOutcome(@Param("id") Long id,
                    @Param("owner") String owner,
                    @Param("status") String status,
                    @Param("nextFireAt") LocalDateTime nextFireAt,
                    @Param("error") String error);

    /**
     * 按 id 取任务行（<b>跨租户，worker 专用</b>）。
     *
     * <p>为什么不能用 {@code BaseMapper.selectById}：调度线程没有租户上下文，拦截器会按
     * “缺少上下文就拒执”的约定直接把这条查询卡掉——于是<b>后台轮询一个任务也执行不了</b>，
     * 而且不报错（只是“任务永不超时”）。本刀集成测试全把 poll 关了，所以这个坑靠
     * {@code SchedulerBackgroundPollTest} 补上证据。
     *
     * <p>跳过注入不等于越租：worker 只拿“该跑哪条”，跑的时候仍在 {@code runAs(task.tenantId)} 里，
     * 业务副作用照旧受隔离。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id, tenant_id, task_type, biz_key, fire_at, status, attempt, last_error,
                   lease_owner, lease_expire_at, create_time, update_time, deleted
            from biz_delay_task
            where id = #{id} and deleted = 0
            """)
    BizDelayTask selectForWorker(@Param("id") Long id);

    /**
     * 登记任务。{@code uk_task_type_key} 是"同一业务事件只登记一次"的裁判，
     * 冲突时的规则要写清（否则重复登记会把已排好的时间推后，等于让超时永不触发）：
     * <ul>
     *   <li>PENDING/FAILED：只把 fire_at 提前（least），不重置 attempt——
     *       重复下单/重复提醒不能把到期时间推后；</li>
     *   <li>RUNNING：不动，别的实例正在做；</li>
     *   <li>DONE：这条已经了结，再登记就是<b>新一轮的起点</b>，fire_at 直接取新值；
     *       本刀实测：给 DONE 也走 least 会把续排时间被旧的过去时间拽住，
     *       周期任务从此永远“刚完就到期”，每小时的任务变成轮询风暴；
     *       attempt 同时归零，否则上一轮的失败次数会抵掉新一轮的预算；</li>
     *   <li>DEAD：保持不动，只由人工或后台恢复——静默复活一条判死的任务会掩盖真问题。</li>
     * </ul>
     *
     * <p><b>赋值顺序不是排版问题</b>：MySQL 的 {@code ON DUPLICATE KEY UPDATE} 按从左到右执行，
     * 后面的表达式读到的是<b>前面已赋值的新值</b>。所以上面那些“按旧 status 分流”的 case
     * 必须全部写在 {@code status = ...} 前面；把它们排在后面，{@code status='DONE'} 就永远不成立，
     * attempt 归零会静默失效（看起来对、跑起来错，还很难查）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            insert into biz_delay_task
                (id, tenant_id, task_type, biz_key, fire_at, status, attempt, create_time, update_time, deleted)
            values (#{id}, #{tenantId}, #{taskType}, #{bizKey}, #{fireAt}, 'PENDING', 0, now(3), now(3), 0)
            on duplicate key update
                attempt = case when status = 'DONE' then 0 else attempt end,
                fire_at = case when status = 'DONE' then values(fire_at)
                               when status in ('PENDING','FAILED') then least(fire_at, values(fire_at))
                               else fire_at end,
                status = case when status = 'DONE' then 'PENDING' else status end,
                update_time = now(3)
            """)
    int register(@Param("id") Long id,
                 @Param("tenantId") Long tenantId,
                 @Param("taskType") String taskType,
                 @Param("bizKey") String bizKey,
                 @Param("fireAt") LocalDateTime fireAt);

    /** 对账与指标用：某类型还有多少待办（不受租户过滤影响）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select count(*) from biz_delay_task
             where deleted = 0 and status in ('PENDING','FAILED')
               and fire_at <= date_add(now(3), interval 1 hour)
            """)
    int countDueBacklog();
}
