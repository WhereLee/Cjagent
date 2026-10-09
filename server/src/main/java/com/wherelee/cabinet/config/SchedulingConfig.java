package com.wherelee.cabinet.config;

import com.wherelee.cabinet.application.task.DelayTaskService;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.infrastructure.mapper.SysTenantMapper;
import com.wherelee.cabinet.domain.entity.SysTenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 开启 Spring 调度。
 *
 * <p>注意这里只负责"让 @Scheduled 生效"，真正的触发策略在 infrastructure/task 的三个实现里。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /**
     * 周期任务的登记器，跟着轮询开关走（{@code cabinet.scheduler.poll-enabled=false} 则不起）。
     * 集成测试把它关掉，避免后台线程与用例断言并发——那会得到“偶尔红”的测试，那不是稳定性而是不可测。
     */
    @Component
    @ConditionalOnProperty(name = "cabinet.scheduler.poll-enabled", havingValue = "true", matchIfMissing = true)
    public static class PeriodicTaskBootstrap {

        private static final Logger log = LoggerFactory.getLogger(PeriodicTaskBootstrap.class);

        private final DelayTaskService tasks;
        private final SysTenantMapper tenantMapper;

        @Value("${cabinet.scheduler.sync-interval-minutes:10}")
        private long syncIntervalMinutes;

        @Value("${cabinet.scheduler.reconcile-interval-minutes:30}")
        private long reconcileIntervalMinutes;

        @Value("${cabinet.scheduler.sweep-interval-minutes:5}")
        private long sweepIntervalMinutes;

        public PeriodicTaskBootstrap(DelayTaskService tasks, SysTenantMapper tenantMapper) {
            this.tasks = tasks;
            this.tenantMapper = tenantMapper;
        }

        /**
         * 周期任务的登记器：每个租户各一条校准/对账/指令扫街。
         *
         * <p>为什么还要"再登记"：{@code uk_task_type_key} 保证同一租户同一时刻只有一条待办，
         * 所以这一轮只是把下一次的时间推到未来（已 PENDING 的用 least 不推后），不会堆积。
         * 这也是<b>幂等登记</b>的意义：进程重启、多实例同时跑，登记结果都一样。
         */
        @Scheduled(fixedDelayString = "${cabinet.scheduler.bootstrap-interval-ms:300000}",
                initialDelayString = "${cabinet.scheduler.bootstrap-initial-delay-ms:60000}")
        public void register() {
            List<SysTenant> tenants = tenantMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysTenant>lambdaQuery()
                            .eq(SysTenant::getStatus, 1));
            LocalDateTime now = LocalDateTime.now();
            for (SysTenant tenant : tenants) {
                tasks.schedule(TaskType.FREE_SET_SYNC, String.valueOf(tenant.getId()), tenant.getId(),
                        now.plusMinutes(syncIntervalMinutes));
                tasks.schedule(TaskType.LEDGER_RECONCILE, String.valueOf(tenant.getId()), tenant.getId(),
                        now.plusMinutes(reconcileIntervalMinutes));
                // 指令扫街：盖“下发写了、收敛没跑、进程死了”这一类逐条提醒盖不住的现场
                tasks.schedule(TaskType.COMMAND_SWEEP, String.valueOf(tenant.getId()), tenant.getId(),
                        now.plusMinutes(sweepIntervalMinutes));
            }
            log.debug("周期任务登记完成，租户数={}", tenants.size());
        }
    }
}
