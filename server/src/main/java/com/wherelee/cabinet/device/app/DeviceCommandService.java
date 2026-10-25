package com.wherelee.cabinet.device.app;

import com.wherelee.cabinet.device.api.DeviceChannel;
import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.device.api.CommandPort;
import com.wherelee.cabinet.device.api.CommandSnapshot;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.device.domain.BizDeviceCommand;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandState;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.device.infra.BizDeviceCommandMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class DeviceCommandService implements CommandPort {

    private static final Logger log = LoggerFactory.getLogger(DeviceCommandService.class);
    private static final String FAIL_KEY_PREFIX = "cab:dev:fail:";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REPORT_PREFIX = "cmd:";

    private final BizDeviceCommandMapper commandMapper;
    private final DeviceFaultTracker faultTracker;
    private final DeviceReportJournal reports;
    private final SlotQueryPort slotQueries;
    private final SlotPort slotStates;
    private final DeviceChannel channel;
    private final StringRedisTemplate redis;
    private final TransactionTemplate txTemplate;

    @Value("${cabinet.device.receipt-timeout-ms:2500}")
    private long receiptTimeoutMs;

    @Value("${cabinet.device.fail-threshold:3}")
    private int failThreshold;

    @Value("${cabinet.scheduler.command-rescan-seconds:30}")
    private long rescanSeconds;

    private final org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler;

    public DeviceCommandService(BizDeviceCommandMapper commandMapper,
                                DeviceFaultTracker faultTracker,
                                DeviceReportJournal reports,
                                SlotQueryPort slotQueries,
                                SlotPort slotStates,
                                DeviceChannel channel,
                                StringRedisTemplate redis,
                                TransactionTemplate txTemplate,
                                org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler) {
        this.commandMapper = commandMapper;
        this.faultTracker = faultTracker;
        this.reports = reports;
        this.slotQueries = slotQueries;
        this.slotStates = slotStates;
        this.channel = channel;
        this.redis = redis;
        this.txTemplate = txTemplate;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public long countAttempts(String requestIdPrefix) {
        if (requestIdPrefix == null || requestIdPrefix.isBlank()) {
            return 0L;
        }
        return commandMapper.selectCount(Wrappers.<BizDeviceCommand>lambdaQuery()
                .likeRight(BizDeviceCommand::getRequestId, requestIdPrefix));
    }

    @Override
    public boolean anyOpenSucceeded(Long orderId) {
        return commandMapper.selectCount(Wrappers.<BizDeviceCommand>lambdaQuery()
                .eq(BizDeviceCommand::getOrderId, orderId)
                .in(BizDeviceCommand::getAction, CommandAction.OPEN, CommandAction.OPEN_TEMP)
                .eq(BizDeviceCommand::getStatus, CommandState.SUCCEEDED)) > 0;
    }

    @Override
    public java.util.List<CommandSnapshot> byOrder(Long orderId, int limit) {
        if (orderId == null) {
            return java.util.List.of();
        }
        int size = Math.max(1, Math.min(limit, 200));
        return commandMapper.selectList(Wrappers.<BizDeviceCommand>lambdaQuery()
                        .eq(BizDeviceCommand::getOrderId, orderId)
                        .orderByDesc(BizDeviceCommand::getSentAt)
                        .last("limit " + size))
                .stream()
                .map(c -> new CommandSnapshot(c.getId(), c.getRequestId(),
                        c.getAction() == null ? null : c.getAction().name(),
                        c.getStatus() == null ? null : c.getStatus().name(),
                        c.getRetryCount() == null ? 0 : c.getRetryCount(),
                        c.getSentAt(), c.getAckedAt(), c.getLastError()))
                .toList();
    }

    @Override
    public CommandOutcome dispatch(Long cabinetId, Long slotId, CommandAction action,
                                   Long orderId, String requestId) {
        CabinetSnapshot cabinet = cabinetId == null ? null : slotQueries.cabinet(cabinetId).orElse(null);
        if (cabinet == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "柜机不存在，无法下发指令 cabinetId=" + cabinetId);
        }
        BizDeviceCommand existing = commandMapper.selectByRequestId(requestId);
        if (existing != null) {
            log.info("指令幂等命中 requestId={} status={}", requestId, existing.getStatus());
            return new CommandOutcome(existing.getId(), existing.getStatus(),
                    existing.getStatus() == CommandState.SUCCEEDED, false, null, "重复请求，返回首次结果");
        }

        if (!channel.available(cabinet.id())) {
            return offlineOutcome(cabinet, slotId, action, requestId);
        }

        Long commandId = txTemplate.execute(status -> {
            BizDeviceCommand command = newCommand(cabinet, slotId, action, orderId, requestId);
            commandMapper.insert(command);
            command.transitTo(CommandState.SENT);
            command.setSentAt(LocalDateTime.now());
            if (commandMapper.updateById(command) == 0) {
                throw new BizException(ResultCode.SYSTEM_ERROR, "指令下发状态未能写入 requestId=" + requestId);
            }
            return command.getId();
        });

        DeviceChannel.CommandReceipt receipt = awaitReceipt(cabinet.id(), slotId, action, requestId);

        final Long cmdId = commandId;
        return txTemplate.execute(status -> applyReceipt(cmdId, cabinet, slotId, receipt));
    }

    DeviceChannel.CommandReceipt awaitReceipt(Long cabinetId, Long slotId, CommandAction action,
                                              String requestId) {
        try {
            return channel.send(new DeviceChannel.CommandTicket(requestId, cabinetId, slotId, action))
                    .get(receiptTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("设备回执超时 cabinetId={} slotId={} requestId={} timeoutMs={}",
                    cabinetId, slotId, requestId, receiptTimeoutMs);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.SYSTEM_ERROR, "等待设备回执被中断，请重试");
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("设备回执异常 requestId={} cause={}", requestId, String.valueOf(e.getCause()));
            return null;
        }
    }

    CommandOutcome applyReceipt(Long commandId, CabinetSnapshot cabinet, Long slotId,
                               DeviceChannel.CommandReceipt receipt) {
        BizDeviceCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "指令记录消失，需要人工核对 id=" + commandId);
        }

        if (receipt == null) {
            transit(command, CommandState.TIMEOUT);
            command.setLastError("回执超时");
            if (!claimTransition(command)) {
                return convergedElsewhere(command);
            }
            faultTracker.recordFault(cabinet.id(), slotId, FaultType.NO_REPORT, FaultType.Action.ALARM,
                    faultTracker.failureCount(cabinet.id()) + 1, "回执超时，等待重试或人工");
            int fails = faultTracker.countFailure(cabinet.id());
            scheduleRescan(cabinet.tenantId(), command.getRequestId());
            return faultTracker.maybeDisable(cabinet, slotId, fails, command.getId(), CommandState.TIMEOUT, FaultType.NO_REPORT,
                    "开柜超时，请稍后重试或联系工作人员");
        }

        boolean stale = reports.write(cabinet, slotId, command, receipt);

        if (stale) {
            log.info("回执序号过旧或重复，不推进状态 requestId={} seq={}", receipt.requestId(), receipt.seq());
            return new CommandOutcome(command.getId(), command.getStatus(), false, true, null,
                    "设备上报为旧事件，未改变状态");
        }

        return converge(command, cabinet, receipt);
    }

    CommandOutcome converge(BizDeviceCommand command, CabinetSnapshot cabinet,
                            DeviceChannel.CommandReceipt receipt) {
        Long slotId = command.getSlotId();

        if (receipt.executedSlotId() != null && !receipt.executedSlotId().equals(slotId)) {
            transit(command, CommandState.FAILED);
            command.setLastError("回执格口与指令不符");
            if (!claimTransition(command)) {
                return convergedElsewhere(command);
            }
            faultTracker.recordFault(cabinet.id(), slotId, FaultType.TARGET_MISMATCH, FaultType.Action.MARK_UNAVAILABLE,
                    faultTracker.failureCount(cabinet.id()) + 1,
                    "设备操作了 slot " + receipt.executedSlotId() + " 而非指令目标 " + slotId);
            int fails = faultTracker.countFailure(cabinet.id());
            return faultTracker.maybeDisable(cabinet, slotId, fails, command.getId(), CommandState.FAILED, FaultType.TARGET_MISMATCH,
                    "柜机响应异常，请联系工作人员");
        }

        if (!receipt.success()) {
            transit(command, CommandState.FAILED);
            command.setLastError(receipt.detail());
            if (!claimTransition(command)) {
                return convergedElsewhere(command);
            }
            if (command.getAction() == CommandAction.PREPARE) {
                log.warn("预备开失败，不计入停用柜机计数 cabinetId={} slotId={} detail={}",
                        cabinet.id(), slotId, receipt.detail());
                return new CommandOutcome(command.getId(), CommandState.FAILED, false, false,
                        FaultType.OPEN_FAILED, "柜机回报无法准备开柜，请重试或换一台柜机");
            }
            faultTracker.recordFault(cabinet.id(), slotId, FaultType.OPEN_FAILED, FaultType.Action.ALARM,
                    faultTracker.failureCount(cabinet.id()) + 1, receipt.detail());
            int fails = faultTracker.countFailure(cabinet.id());
            return faultTracker.maybeDisable(cabinet, slotId, fails, command.getId(), CommandState.FAILED, FaultType.OPEN_FAILED,
                    "开柜失败，请重试或换一台柜机");
        }

        if (!receipt.sensorConfirmed()) {
            transit(command, CommandState.SUCCEEDED);
            if (!claimTransition(command)) {
                return convergedElsewhere(command);
            }
            faultTracker.clearFailure(cabinet.id());
            faultTracker.recordFault(cabinet.id(), slotId, FaultType.DOOR_NOT_CLOSED, FaultType.Action.ALARM,
                    faultTracker.failureCount(cabinet.id()), "设备称已关门，传感器未确认");
            return new CommandOutcome(command.getId(), CommandState.SUCCEEDED, false, false,
                    FaultType.DOOR_NOT_CLOSED, "柜门状态未确认，需要核验");
        }

        transit(command, CommandState.SUCCEEDED);
        command.setAckedAt(LocalDateTime.now());
        command.setLastError(null);
        if (!claimTransition(command)) {
            return convergedElsewhere(command);
        }
        if (command.getAction() == CommandAction.PREPARE) {
            log.debug("预备开成功，不重置停用计数 cabinetId={} slotId={}", cabinet.id(), slotId);
        } else {
            faultTracker.clearFailure(cabinet.id());
        }
        return new CommandOutcome(command.getId(), CommandState.SUCCEEDED, true, false, null, null);
    }

    private void scheduleRescan(Long tenantId, String requestId) {
        DelayTaskService scheduler = taskScheduler.getIfAvailable();
        if (scheduler == null) {
            log.warn("没有可用的调度器，超时指令不会自动回扫 requestId={}", requestId);
            return;
        }
        try {
            scheduler.schedule(TaskType.COMMAND_RESCAN, requestId, tenantId,
                    LocalDateTime.now().plusSeconds(rescanSeconds));
        } catch (RuntimeException e) {
            log.error("登记指令回扫失败（等扫街保底补上）requestId={}", requestId, e);
        }
    }

    private CommandOutcome offlineOutcome(CabinetSnapshot cabinet, Long slotId, CommandAction action, String requestId) {
        faultTracker.recordFault(cabinet.id(), slotId, FaultType.OFFLINE, FaultType.Action.ALARM,
                faultTracker.failureCount(cabinet.id()), "柜机离线");
        if (action.isRetrieveLike()) {
            return new CommandOutcome(null, CommandState.PENDING, false, false, FaultType.OFFLINE,
                    "柜机离线，取件已转人工处理，请勿离开现场");
        }
        throw new BizException(ResultCode.MIDDLEWARE_UNAVAILABLE, "柜机离线，暂不可存件，请换一台");
    }

    private void transit(BizDeviceCommand command, CommandState target) {
        if (command.getStatus() == null) {
            command.initStatus();
        }
        command.transitTo(target);
    }

    private boolean claimTransition(BizDeviceCommand command) {
        return commandMapper.updateById(command) == 1;
    }

    private CommandOutcome convergedElsewhere(BizDeviceCommand command) {
        BizDeviceCommand fresh = commandMapper.selectById(command.getId());
        if (fresh == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "指令记录消失，需要人工核对 id=" + command.getId());
        }
        log.info("指令已被并发路径收敛，本次不重复记故障计数与台账 requestId={} status={}",
                fresh.getRequestId(), fresh.getStatus());
        boolean success = fresh.getStatus() == CommandState.SUCCEEDED && fresh.getAckedAt() != null;
        String message = switch (fresh.getStatus()) {
            case SUCCEEDED -> success ? "柜门已开启。" : "柜门状态未确认，需要核验";
            case FAILED -> "开柜失败，请重试或换一台柜机";
            case TIMEOUT -> "开柜超时，请稍后重试或联系工作人员";
            default -> "指令已由并发路径处置";
        };
        return new CommandOutcome(fresh.getId(), fresh.getStatus(), success, true, null, message);
    }

    private BizDeviceCommand newCommand(CabinetSnapshot cabinet, Long slotId, CommandAction action,
                                        Long orderId, String requestId) {
        BizDeviceCommand command = new BizDeviceCommand();
        command.setTenantId(cabinet.tenantId());
        command.setOrderId(orderId);
        command.setCabinetId(cabinet.id());
        command.setSlotId(slotId);
        command.setRequestId(requestId);
        command.setAction(action);
        command.initStatus();
        return command;
    }

}
