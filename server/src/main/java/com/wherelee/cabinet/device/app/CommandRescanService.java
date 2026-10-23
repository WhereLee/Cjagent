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

/**
 * 超时指令的回扫：有流水就重放收敛，有预算就用同一 requestId 重发，预算用尽才判死。
 *
 * <p>为什么从下发用例里拆出来：下发是“用户在柜机前等 2.5 秒”的同步路径，回扫是“人已经走了、
 * 由调度器收尾”的异步路径。两者的触发者、时限与失败语义都不同；混在一个类里时，
 * 回扫的阈值（重试上限）与下发的阈值（回执超时）很容易被后人当成同一个东西。
 *
 * <p>收敛判据仍然只有一份：本类调用 {@link DeviceCommandService#converge}，
 * 不另写一套“回扫版的成功/失败”，否则迟早出现“实时能判谎报、回扫把谎报当成功”。
 */
@Service
public class CommandRescanService {

    private static final Logger log = LoggerFactory.getLogger(CommandRescanService.class);

    private final DeviceCommandService commands;
    private final BizDeviceCommandMapper commandMapper;
    private final DeviceReportJournal reports;
    private final DeviceFaultTracker faultTracker;
    private final SlotQueryPort slotQueries;
    private final TransactionTemplate txTemplate;

    /** 回扫重试上限：超了就判死交人工，不能无限重发（对开门这种物理动作，无限重发是风险）。 */
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

    /**
     * 回扫结果：调用方（调度 handler）需要知道“还要不要再排一轮”与“订单该怎么推”。
     *
     * @param action 本次回扫做了什么（不是指令最终状态，那是 outcome 里的事）
     * @param outcome 收敛结果；{@code null} 表示本条无需处理（已终态或不存在）
     */
    public record RescanResult(String requestId, Long commandId, RescanAction action, CommandOutcome outcome) {
    }

    /** 回扫的四种结局。 */
    public enum RescanAction {
        /** 已经收敛/无需处理 */
        SETTLED,
        /** 用上报流水重放收敛完成 */
        CONVERGED_FROM_REPORT,
        /** 重新下发并已处理 */
        REDISPATCHED,
        /** 重试耗尽，判死交人工 */
        GAVE_UP
    }

    /**
     * 回扫一条没收敛的指令。三种现场三条路，顺序不能换：
     * <ol>
     *   <li><b>有上报流水</b>（回执比超时判定晚到）→ 用流水重放收敛，<b>绝不再下发</b>：
     *       门可能真的已经开过，再发一次就是对同一个逻辑操作执行两遍；</li>
     *   <li>没流水但还有重试预算 → 用<b>同一个 requestId</b> 重新下发（设备端对同 requestId 幂等），
     *       换 ID 等于放弃幂等；</li>
     *   <li>预算用尽 → <b>判死</b>：状态停在 TIMEOUT 不再自动流转，留故障事件与 ERROR，
     *       订单侧由调用方按“正在执行哪个动作”退回可重试或转人工。</li>
     * </ol>
     *
     * <p>为什么等回执不在事务里而回扫也在等：与下发同一条约束（第 9 刀量过连接池）。
     */
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

        // 占用本轮重试名额：影响 0 行说明另一个实例已经先一步动了这条指令，本次不重复做
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

    /** 重试耗尽：不再自动流转，但要把“为什么停”写清楚（没这条记录，事后就是一个无人知道的黑洞）。 */
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
