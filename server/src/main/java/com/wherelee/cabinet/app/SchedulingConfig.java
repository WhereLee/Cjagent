package com.wherelee.cabinet.app;

import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
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

@Configuration
@EnableScheduling
public class SchedulingConfig {

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
                tasks.schedule(TaskType.COMMAND_SWEEP, String.valueOf(tenant.getId()), tenant.getId(),
                        now.plusMinutes(sweepIntervalMinutes));
            }
            log.debug("周期任务登记完成，租户数={}", tenants.size());
        }
    }
}
