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

/**
 * 设备指令用例：下发 → 等回执 → 收敛状态 → 记录故障 → 超时回扫。
 *
 * <p><b>本类最重要的一行设计是"等回执时不开事务"</b>。
 * 如果在 {@code @Transactional} 方法里 future.get(3s)，每个等待中的请求都会<b>占着一个数据库连接</b>；
 * 第 9 刀压测已经量出连接池只有 10 个、事务时长一长吞吐就崩。所以这里用
 * {@link TransactionTemplate} 显式切成两个短事务：写指令 / 收敛回执，中间那段等待<b>不持有连接</b>。
 * 这也是没有把方法标成事务注解的原因——看起来"少写了一层保护"，实际上是拿掉了瓶颈。
 *
 * <p><b>拆事务的代价由回扫支付</b>（第 11 刀欠的账，本刀结清）：中途崩溃会留下
 * “设备开了门但订单没变”的现场，所以回扫（{@link CommandRescanService}）必须能从上报流水重放收敛，
 * 而不是假设“总会有人再点一次”。
 *
 * <p>故障矩阵与这里的分支一一对应（规划 §8）：无回执、谎报关门、错报目标、乱序重放、
 * 离线降级、连续失败停用。每种都有用例断言，不是"写了个 switch 就算支持"。
 */
@Service
public class DeviceCommandService implements CommandPort {

    private static final Logger log = LoggerFactory.getLogger(DeviceCommandService.class);
    private static final String FAIL_KEY_PREFIX = "cab:dev:fail:";

    /** 上报 payload 读写专用 mapper：与定价快照同一条约定（展示层编码器不参与业务算术/往返）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** dedup_key 前缀，改这里就要改 {@code selectLatestForCommand} 的 like。 */
    private static final String REPORT_PREFIX = "cmd:";

    private final BizDeviceCommandMapper commandMapper;
    /** 故障台账与连续失败阈值。 */
    private final DeviceFaultTracker faultTracker;
    /** 上报流水：写入、幂等与从流水重建回执。 */
    private final DeviceReportJournal reports;
    private final SlotQueryPort slotQueries;
    private final SlotPort slotStates;
    private final DeviceChannel channel;
    private final StringRedisTemplate redis;
    private final TransactionTemplate txTemplate;

    @Value("${cabinet.device.receipt-timeout-ms:2500}")
    private long receiptTimeoutMs;

    /** 连续失败到这个次数就把格口标故障、柜机停用（阈值本身是运营参数，不硬编码在分支里）。 */
    @Value("${cabinet.device.fail-threshold:3}")
    private int failThreshold;

    /** 无回执指令多久后回扫（也是重试间隔）。 */
    @Value("${cabinet.scheduler.command-rescan-seconds:30}")
    private long rescanSeconds;

    /**
     * 调度器延迟拿取（同 StorageOrderService：超时回扫的登记会绕回本类，构造期不能互依赖）。
     */
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
            // 同一个 requestId 再来一次：绝不重复下发（设备可能被开两次）
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
            // 刚 insert 的行、同一个事务里紧接着推 SENT：落空只可能是 version 初值这类编程错误。
            // 不能静默——SENT 没写上，回扫就认不出"这条该扫"，指令会永远停在 PENDING。
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
            // 登记回扫：此刻用户已经拿到“请重试”的回复走了，但现场必须有人收
            scheduleRescan(cabinet.tenantId(), command.getRequestId());
            return faultTracker.maybeDisable(cabinet, slotId, fails, command.getId(), CommandState.TIMEOUT, FaultType.NO_REPORT,
                    "开柜超时，请稍后重试或联系工作人员");
        }

        boolean stale = reports.write(cabinet, slotId, command, receipt);

        if (stale) {
            // 旧序号/重复上报：现场保留，状态不动。设备重启后 seq 归零重放是真实场景，
            // 如果让它推进状态，就会把"新一次开柜"的结果覆盖成旧事件
            log.info("回执序号过旧或重复，不推进状态 requestId={} seq={}", receipt.requestId(), receipt.seq());
            return new CommandOutcome(command.getId(), command.getStatus(), false, true, null,
                    "设备上报为旧事件，未改变状态");
        }

        return converge(command, cabinet, receipt);
    }

    /**
     * 把一次回执收敛到指令状态与故障计数。<b>正常路径与回扫重放共用这一段</b>：
     * 两处各写一遍判据，迟早出现“实时能判谎报、回扫把谎报当成功”。
     *
     * <p>调用前提：回执已经过幂等/乱序判定（不在这里写流水），本方法只改指令与故障。
     */
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
                // 预备开失败（定-23，用户 2026-10-11 定“不计入”）：归类仍报 OPEN_FAILED，
                // 因为业务要靠它判“确认坏了”；但**不进 recordFault/maybeDisable**：
                // 预备开只是问一句话、柜机什么都没动，用它去触发“连续失败停用整台柜机”
                // 会把一次通信抖动放大成停一台柜。现场留在指令表（FAILED + last_error）与日志里。
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

        // 设备说成功，但传感器没确认关门：指令层面确实完成了，业务层面不能认账。
        // 这两种"成功"必须分开——把谎报当成功，件就会在没人知道的情况下锁在柜里。
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
            // “不计入”的另一半（用户 2026-10-11 定）：预备开成功**也不清**连续失败计数。
            // 它只是柜机应了一句“我能动”，既不该给停用计数加一笔，也不该把别人攻下的账抹掉：
            // 抹掉就会出现“每次真开都不回话，却永远停用不了这台坑人的柜机”（用例实测到的就是这个）。
            log.debug("预备开成功，不重置停用计数 cabinetId={} slotId={}", cabinet.id(), slotId);
        } else {
            faultTracker.clearFailure(cabinet.id());
        }
        return new CommandOutcome(command.getId(), CommandState.SUCCEEDED, true, false, null, null);
    }

    /** 登记回扫提醒（失败不能影响主链路：没提醒只是收敛得慢，而下发失败会让用户卡在柜机前）。 */
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

    /** 离线时的处置：存件方向直接拒，取件方向判异常留人工出口（降级方向"不可存、可取"）。 */
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

    /**
     * 抢占本次状态推进：影响 0 行说明<b>另一条路径已经收敛过同一条指令</b>，本次必须让位。
     *
     * <p>对手是谁：{@link CommandRescanService} 用迟到流水重放收敛（{@code :112}）、
     * 重发后再收敛（{@code :138}），以及租约过期被接管后的重跑——
     * {@code TaskHandler} 的类注释写明「租约保证不了'一生只执行一次'」，所以重跑是设计内场景。
     *
     * <p>为什么必须判：{@link DeviceFaultTracker#countFailure} 是 Redis INCR、
     * {@link DeviceFaultTracker#recordFault} 是 DB insert，两者都不幂等。
     * 同一条指令被收敛两次，连续失败计数就虚高一倍，虚高到 {@code fail-threshold}
     * 会把一台没坏的柜机停用（{@code maybeDisable}）——故障台账也会留下重复事件行。
     * 这正是 {@code converge} 被抽成实时与回扫共用一段时想避免的"同事件不同结果"，
     * 只是漏洞不在判据表上，而在副作用被执行了几次。
     *
     * <p>手法沿用 {@code CommandRescanService:122-133} 占用重试名额那一处：条件更新影响 0 行即让位。
     */
    private boolean claimTransition(BizDeviceCommand command) {
        return commandMapper.updateById(command) == 1;
    }

    /**
     * 让位之后回放什么：<b>按 DB 真实状态回答，且不执行任何故障计数与台账副作用</b>。
     *
     * <p>标 {@code stale=true} 是因为 {@code OrderDeviceOutcomeService.apply:78-81} 见到 stale
     * 就不改订单状态——本次调用方不该拿一个"别人已经处置过"的事件再推一次状态。
     *
     * <p>{@code faultType} 一律给 null：判不出另一条路径当时归的是哪一类故障
     * （FAILED 可能是串位也可能是开柜失败，DB 里只留了文案没留类型），
     * 而猜错代价更高——{@code OrderDoorSessionService.prepareOnce} 拿 {@code faultType == OPEN_FAILED} 当
     * "柜机自己确认坏了"的唯一判据，误报成它会触发重试→换格→退单。
     * 给 null 让上层落到 SILENT（"我们不知道"），按定-23 的口径就是不动钱不动单。
     */
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
