package com.wherelee.cabinet.device.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.device.api.CommandState;
import com.wherelee.cabinet.device.api.DeviceChannel;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.device.domain.BizDeviceCommand;
import com.wherelee.cabinet.device.infra.BizDeviceCommandMapper;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CommandRescanService {

    private static final Logger log = LoggerFactory.getLogger(CommandRescanService.class);

    private final DeviceCommandService commands;
    private final BizDeviceCommandMapper commandMapper;
    private final DeviceReportJournal reports;
    private final DeviceFaultTracker faultTracker;
    private final SlotQueryPort slotQueries;
    private final TransactionTemplate txTemplate;

    @Value("${cabinet.device.rescan-max-retry:2}")
    private int rescanMaxRetry;

    public CommandRescanService(DeviceCommandService commands,
                                BizDeviceCommandMapper commandMapper,
                                DeviceReportJournal reports,
                                DeviceFaultTracker faultTracker,
                                SlotQueryPort slotQueries,
                                TransactionTemplate txTemplate) {
        this.commands = commands;
        this.commandMapper = commandMapper;
        this.reports = reports;
        this.faultTracker = faultTracker;
        this.slotQueries = slotQueries;
        this.txTemplate = txTemplate;
    }

    public record RescanResult(String requestId, Long commandId, RescanAction action, CommandOutcome outcome) {
    }

    public enum RescanAction {
        SETTLED,
        CONVERGED_FROM_REPORT,
        REDISPATCHED,
        GAVE_UP
    }

    public RescanResult rescan(String requestId) {
        BizDeviceCommand command = commandMapper.selectByRequestId(requestId);
        if (command == null) {
            log.info("回扫跳过：指令不存在 requestId={}", requestId);
            return new RescanResult(requestId, null, RescanAction.SETTLED, null);
        }
        if (command.getStatus().isTerminal()) {
            return new RescanResult(requestId, command.getId(), RescanAction.SETTLED, null);
        }
        CabinetSnapshot cabinet = slotQueries.cabinet(command.getCabinetId()).orElse(null);
        if (cabinet == null) {
            log.error("回扫无法进行：柜机不存在 cabinetId={} requestId={}", command.getCabinetId(), requestId);
            return new RescanResult(requestId, command.getId(), RescanAction.SETTLED, null);
        }

        DeviceChannel.CommandReceipt late = reports.latestReceipt(requestId);
        if (late != null) {
            log.info("回扫发现迟到的回执，按流水重放收敛 requestId={} seq={}", requestId, late.seq());
            CommandOutcome outcome = txTemplate.execute(status -> commands.converge(command, cabinet, late));
            return new RescanResult(requestId, command.getId(), RescanAction.CONVERGED_FROM_REPORT, outcome);
        }

        int retries = command.getRetryCount() == null ? 0 : command.getRetryCount();
        if (retries >= rescanMaxRetry) {
            CommandOutcome outcome = txTemplate.execute(status -> giveUp(command, cabinet, retries));
            return new RescanResult(requestId, command.getId(), RescanAction.GAVE_UP, outcome);
        }

        Boolean claimed = txTemplate.execute(status -> {
            if (command.getStatus() == CommandState.TIMEOUT) {
                command.transitTo(CommandState.SENT);
            }
            command.setRetryCount(retries + 1);
            return commandMapper.updateById(command) == 1;
        });
        if (!Boolean.TRUE.equals(claimed)) {
            log.info("回扫重试被人抢先，本次放弃 requestId={}", requestId);
            return new RescanResult(requestId, command.getId(), RescanAction.SETTLED, null);
        }

        DeviceChannel.CommandReceipt receipt = commands.awaitReceipt(cabinet.id(), command.getSlotId(),
                command.getAction(), requestId);
        final Long commandId = command.getId();
        CommandOutcome outcome = txTemplate.execute(status ->
                commands.applyReceipt(commandId, cabinet, command.getSlotId(), receipt));
        return new RescanResult(requestId, commandId, RescanAction.REDISPATCHED, outcome);
    }

    private CommandOutcome giveUp(BizDeviceCommand command, CabinetSnapshot cabinet, int retries) {
        command.setLastError("重试 " + retries + " 次仍无回执，转人工");
        if (commandMapper.updateById(command) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "指令已被并发修改，回扫下一轮重试 id=" + command.getId());
        }
        faultTracker.recordFault(cabinet.id(), command.getSlotId(), FaultType.NO_REPORT, FaultType.Action.ALARM,
                faultTracker.failureCount(cabinet.id()), "重试 " + retries + " 次仍无回执，需人工核验现场");
        log.error("指令判死需人工处理 requestId={} cabinetId={} slotId={} action={} 重试次数={}",
                command.getRequestId(), cabinet.id(), command.getSlotId(), command.getAction(), retries);
        return new CommandOutcome(command.getId(), command.getStatus(), false, false, FaultType.NO_REPORT,
                "开柜结果未能确认，已转人工处理");
    }
}
