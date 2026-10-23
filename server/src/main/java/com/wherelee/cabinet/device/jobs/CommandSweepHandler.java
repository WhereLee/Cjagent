package com.wherelee.cabinet.device.jobs;

import com.wherelee.cabinet.platform.scheduling.TaskHandler;
import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.device.infra.BizDeviceCommandMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class CommandSweepHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandSweepHandler.class);

    private final BizDeviceCommandMapper commandMapper;
    private final org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler;

    @Value("${cabinet.scheduler.sweep-stale-minutes:2}")
    private long staleMinutes;

    @Value("${cabinet.scheduler.sweep-batch-size:50}")
    private int sweepBatchSize;

    CommandSweepHandler(BizDeviceCommandMapper commandMapper,
                        org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler) {
        this.commandMapper = commandMapper;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public TaskType type() {
        return TaskType.COMMAND_SWEEP;
    }

    @Override
    public boolean handle(BizDelayTask task) {
        DelayTaskService scheduler = taskScheduler.getIfAvailable();
        if (scheduler == null) {
            log.warn("没有调度器，指令扫街本轮什么也不能做");
            return true;
        }
        List<String> stuck = commandMapper.findStuckRequestIds(
                LocalDateTime.now().minusMinutes(staleMinutes), sweepBatchSize);
        LocalDateTime fireAt = LocalDateTime.now();
        for (String requestId : stuck) {
            scheduler.schedule(TaskType.COMMAND_RESCAN, requestId, task.getTenantId(), fireAt);
        }
        if (!stuck.isEmpty()) {
            log.warn("扫到卡住的指令 {} 条，已登记回扫（单轮上限 {}）",
                    stuck.size(), sweepBatchSize);
        }
        return true;
    }
}
