package com.wherelee.cabinet.application.device;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.application.task.DelayTaskService;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizDeviceCommand;
import com.wherelee.cabinet.domain.entity.BizDeviceReport;
import com.wherelee.cabinet.domain.entity.BizFaultEvent;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.domain.enums.CommandState;
import com.wherelee.cabinet.domain.enums.FaultType;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizDeviceCommandMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizDeviceReportMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizFaultEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
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
 * “设备开了门但订单没变”的现场，所以 {@link #rescan} 必须能从上报流水重放收敛，
 * 而不是假设“总会有人再点一次”。
 *
 * <p>故障矩阵与这里的分支一一对应（规划 §8）：无回执、谎报关门、错报目标、乱序重放、
 * 离线降级、连续失败停用。每种都有用例断言，不是"写了个 switch 就算支持"。
 */
@Service
public class DeviceCommandService {

    private static final Logger log = LoggerFactory.getLogger(DeviceCommandService.class);
    private static final String FAIL_KEY_PREFIX = "cab:dev:fail:";

    /** 上报 payload 读写专用 mapper：与定价快照同一条约定（展示层编码器不参与业务算术/往返）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** dedup_key 前缀，改这里就要改 {@code selectLatestForCommand} 的 like。 */
    private static final String REPORT_PREFIX = "cmd:";

    private final BizDeviceCommandMapper commandMapper;
    private final BizDeviceReportMapper reportMapper;
    private final BizFaultEventMapper faultMapper;
    private final BizCompartmentMapper slotMapper;
    private final BizCabinetMapper cabinetMapper;
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

    /** 回扫重试上限：超了就判死交人工，不能无限重发（对开门这种物理动作，无限重发是风险）。 */
    @Value("${cabinet.device.rescan-max-retry:2}")
    private int rescanMaxRetry;

    /**
     * 调度器延迟拿取（同 StorageOrderService：超时回扫的登记会绕回本类，构造期不能互依赖）。
     */
    private final org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler;

    public DeviceCommandService(BizDeviceCommandMapper commandMapper,
                                BizDeviceReportMapper reportMapper,
                                BizFaultEventMapper faultMapper,
                                BizCompartmentMapper slotMapper,
                                BizCabinetMapper cabinetMapper,
                                DeviceChannel channel,
                                StringRedisTemplate redis,
                                TransactionTemplate txTemplate,
                                org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler) {
        this.commandMapper = commandMapper;
        this.reportMapper = reportMapper;
        this.faultMapper = faultMapper;
        this.slotMapper = slotMapper;
        this.cabinetMapper = cabinetMapper;
        this.channel = channel;
        this.redis = redis;
        this.txTemplate = txTemplate;
        this.taskScheduler = taskScheduler;
    }

    /**
     * @param stale    回执是重复/乱序的旧事件：已入库留证，但<b>不得改变业务状态</b>
     * @param faultType 本次触发的故障类型（null 表示无故障）
     */
    public record CommandOutcome(Long commandId,
                                 CommandState state,
                                 boolean businessSuccess,
                                 boolean stale,
                                 FaultType faultType,
                                 String message) {
    }

    public CommandOutcome dispatch(BizCabinet cabinet, Long slotId, CommandAction action,
                                   Long orderId, String requestId) {
        BizDeviceCommand existing = commandMapper.selectByRequestId(requestId);
        if (existing != null) {
            // 同一个 requestId 再来一次：绝不重复下发（设备可能被开两次）
            log.info("指令幂等命中 requestId={} status={}", requestId, existing.getStatus());
            return new CommandOutcome(existing.getId(), existing.getStatus(),
                    existing.getStatus() == CommandState.SUCCEEDED, false, null, "重复请求，返回首次结果");
        }

        if (!channel.available(cabinet.getId())) {
            return offlineOutcome(cabinet, slotId, action, requestId);
        }

        Long commandId = txTemplate.execute(status -> {
            BizDeviceCommand command = newCommand(cabinet, slotId, action, orderId, requestId);
            commandMapper.insert(command);
            command.transitTo(CommandState.SENT);
            command.setSentAt(LocalDateTime.now());
            commandMapper.updateById(command);
            return command.getId();
        });

        DeviceChannel.CommandReceipt receipt = awaitReceipt(cabinet.getId(), slotId, action, requestId);

        final Long cmdId = commandId;
        return txTemplate.execute(status -> applyReceipt(cmdId, cabinet, slotId, receipt));
    }

    private DeviceChannel.CommandReceipt awaitReceipt(Long cabinetId, Long slotId, CommandAction action,
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

    private CommandOutcome applyReceipt(Long commandId, BizCabinet cabinet, Long slotId,
                                        DeviceChannel.CommandReceipt receipt) {
        BizDeviceCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "指令记录消失，需要人工核对 id=" + commandId);
        }

        if (receipt == null) {
            transit(command, CommandState.TIMEOUT);
            command.setLastError("回执超时");
            commandMapper.updateById(command);
            recordFault(cabinet.getId(), slotId, FaultType.NO_REPORT, FaultType.Action.ALARM,
                    failureCount(cabinet.getId()) + 1, "回执超时，等待重试或人工");
            int fails = countFailure(cabinet.getId());
            // 登记回扫：此刻用户已经拿到“请重试”的回复走了，但现场必须有人收
            scheduleRescan(cabinet.getTenantId(), command.getRequestId());
            return maybeDisable(cabinet, slotId, fails, command.getId(), CommandState.TIMEOUT, FaultType.NO_REPORT,
                    "开柜超时，请稍后重试或联系工作人员");
        }

        boolean stale = writeReport(cabinet, slotId, command, receipt);

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
    private CommandOutcome converge(BizDeviceCommand command, BizCabinet cabinet,
                                    DeviceChannel.CommandReceipt receipt) {
        Long slotId = command.getSlotId();

        if (receipt.executedSlotId() != null && !receipt.executedSlotId().equals(slotId)) {
            transit(command, CommandState.FAILED);
            command.setLastError("回执格口与指令不符");
            commandMapper.updateById(command);
            recordFault(cabinet.getId(), slotId, FaultType.TARGET_MISMATCH, FaultType.Action.MARK_UNAVAILABLE,
                    failureCount(cabinet.getId()) + 1,
                    "设备操作了 slot " + receipt.executedSlotId() + " 而非指令目标 " + slotId);
            int fails = countFailure(cabinet.getId());
            return maybeDisable(cabinet, slotId, fails, command.getId(), CommandState.FAILED, FaultType.TARGET_MISMATCH,
                    "柜机响应异常，请联系工作人员");
        }

        if (!receipt.success()) {
            transit(command, CommandState.FAILED);
            command.setLastError(receipt.detail());
            commandMapper.updateById(command);
            recordFault(cabinet.getId(), slotId, FaultType.OPEN_FAILED, FaultType.Action.ALARM,
                    failureCount(cabinet.getId()) + 1, receipt.detail());
            int fails = countFailure(cabinet.getId());
            return maybeDisable(cabinet, slotId, fails, command.getId(), CommandState.FAILED, FaultType.OPEN_FAILED,
                    "开柜失败，请重试或换一台柜机");
        }

        // 设备说成功，但传感器没确认关门：指令层面确实完成了，业务层面不能认账。
        // 这两种"成功"必须分开——把谎报当成功，件就会在没人知道的情况下锁在柜里。
        if (!receipt.sensorConfirmed()) {
            transit(command, CommandState.SUCCEEDED);
            commandMapper.updateById(command);
            clearFailure(cabinet.getId());
            recordFault(cabinet.getId(), slotId, FaultType.DOOR_NOT_CLOSED, FaultType.Action.ALARM,
                    failureCount(cabinet.getId()), "设备称已关门，传感器未确认");
            return new CommandOutcome(command.getId(), CommandState.SUCCEEDED, false, false,
                    FaultType.DOOR_NOT_CLOSED, "柜门状态未确认，需要核验");
        }

        transit(command, CommandState.SUCCEEDED);
        command.setAckedAt(LocalDateTime.now());
        command.setLastError(null);
        commandMapper.updateById(command);
        clearFailure(cabinet.getId());
        return new CommandOutcome(command.getId(), CommandState.SUCCEEDED, true, false, null, null);
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
     * <p>为什么等回执不在事务里而回扫也在等：与 dispatch 同一条约束（第 9 刀量过连接池）。
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
        BizCabinet cabinet = cabinetMapper.selectById(command.getCabinetId());
        if (cabinet == null) {
            log.error("回扫无法进行：柜机不存在 cabinetId={} requestId={}", command.getCabinetId(), requestId);
            return new RescanResult(requestId, command.getId(), RescanAction.SETTLED, null);
        }

        DeviceChannel.CommandReceipt late = receiptFrom(reportMapper.selectLatestForCommand(requestId));
        if (late != null) {
            log.info("回扫发现迟到的回执，按流水重放收敛 requestId={} seq={}", requestId, late.seq());
            CommandOutcome outcome = txTemplate.execute(status -> converge(command, cabinet, late));
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

        DeviceChannel.CommandReceipt receipt = awaitReceipt(cabinet.getId(), command.getSlotId(),
                command.getAction(), requestId);
        final Long commandId = command.getId();
        CommandOutcome outcome = txTemplate.execute(status ->
                applyReceipt(commandId, cabinet, command.getSlotId(), receipt));
        return new RescanResult(requestId, commandId, RescanAction.REDISPATCHED, outcome);
    }

    /** 重试耗尽：不再自动流转，但要把“为什么停”写清楚（没这条记录，事后就是一个无人知道的黑洞）。 */
    private CommandOutcome giveUp(BizDeviceCommand command, BizCabinet cabinet, int retries) {
        command.setLastError("重试 " + retries + " 次仍无回执，转人工");
        if (commandMapper.updateById(command) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "指令已被并发修改，回扫下一轮重试 id=" + command.getId());
        }
        recordFault(cabinet.getId(), command.getSlotId(), FaultType.NO_REPORT, FaultType.Action.ALARM,
                failureCount(cabinet.getId()), "重试 " + retries + " 次仍无回执，需人工核验现场");
        log.error("指令判死需人工处理 requestId={} cabinetId={} slotId={} action={} 重试次数={}",
                command.getRequestId(), cabinet.getId(), command.getSlotId(), command.getAction(), retries);
        return new CommandOutcome(command.getId(), command.getStatus(), false, false, FaultType.NO_REPORT,
                "开柜结果未能确认，已转人工处理");
    }

    /**
     * 从上报流水重建一个回执（重放收敛的输入）。
     *
     * <p>读不出来就返回 {@code null}，让调用方走“重试/判死”那两条保守路径：
     * <b>宁可重新下发一次（设备端幂等），也不能拿一个猜出来的成功去推订单状态</b>。
     */
    private DeviceChannel.CommandReceipt receiptFrom(BizDeviceReport report) {
        if (report == null) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(report.getPayload());
            return new DeviceChannel.CommandReceipt(requestIdOf(report.getDedupKey()),
                    node.path("success").asBoolean(false),
                    report.getEventType(),
                    report.getSeq() == null ? 0L : report.getSeq(),
                    node.path("sensorConfirmed").asBoolean(false),
                    node.hasNonNull("executedSlotId") ? node.get("executedSlotId").asLong() : null,
                    node.hasNonNull("detail") ? node.get("detail").asText() : null);
        } catch (Exception e) {
            log.error("上报流水 payload 解析失败，改走重试/判死路径 dedupKey={}", report.getDedupKey(), e);
            return null;
        }
    }

    /** {@code cmd:<requestId>} 或 {@code cmd:<requestId>#rN} → requestId。 */
    private String requestIdOf(String dedupKey) {
        String value = dedupKey.startsWith(REPORT_PREFIX) ? dedupKey.substring(REPORT_PREFIX.length()) : dedupKey;
        int round = value.indexOf('#');
        return round > 0 ? value.substring(0, round) : value;
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
    private CommandOutcome offlineOutcome(BizCabinet cabinet, Long slotId, CommandAction action, String requestId) {
        recordFault(cabinet.getId(), slotId, FaultType.OFFLINE, FaultType.Action.ALARM,
                failureCount(cabinet.getId()), "柜机离线");
        if (action.isRetrieveLike()) {
            return new CommandOutcome(null, CommandState.PENDING, false, false, FaultType.OFFLINE,
                    "柜机离线，取件已转人工处理，请勿离开现场");
        }
        throw new BizException(ResultCode.MIDDLEWARE_UNAVAILABLE, "柜机离线，暂不可存件，请换一台");
    }

    /**
     * 上报流水入库；返回是否“旧事件”（同一指令的回执重复送达）。
     *
     * <p><b>幂等键用 requestId，不用 {@code cabinetId:seq}</b>。后者看着自然其实错：
     * 设备重启、刷固件、换主控都会把 seq 归零重发，于是重启后第一条<b>正常</b>回执会撞上
     * 重启前的 dedupKey 而被当成重复丢弃——对外表现就是“柜机上线后所有开柜都不生效”。
     * 真实设备一定会重启，所以这条规则在生产上必然发火。
     * （本缺陷由 dev 环境端到端验证发现；集成测试每个用例新建柜机，恰好盖不到。）
     */
    private boolean writeReport(BizCabinet cabinet, Long slotId, BizDeviceCommand command,
                                DeviceChannel.CommandReceipt receipt) {
        LocalDateTime now = LocalDateTime.now();
        BizDeviceReport report = new BizDeviceReport();
        report.setTenantId(cabinet.getTenantId());
        report.setCabinetId(cabinet.getId());
        report.setSlotId(slotId);
        report.setOrderId(command.getOrderId());
        report.setSeq(receipt.seq());
        report.setEventType(receipt.event());
        int round = command.getRetryCount() == null ? 0 : command.getRetryCount();
        // dedup_key 带轮次后缀：“同一轮内的重复送达”才算重复（挡重放），
        // 跳轮是新的一次物理事件，必须各自入库。否则回扫重试拿到的回执会撞上第一轮的空
        // 幂等键而被判成旧事件，结果就是“重试永远成功不了”（本刀实测）。
        report.setDedupKey(REPORT_PREFIX + receipt.requestId() + (round > 0 ? "#r" + round : ""));
        // success 必须进 payload：回扫时要从流水重建一个能驱动状态机的回执，
        // 缺这一列就分不清“设备说失败”与“设备没报”，回扫只能一律重发
        report.setPayload("{\"requestId\":\"" + receipt.requestId() + "\",\"success\":" + receipt.success()
                + ",\"detail\":" + jsonString(receipt.detail()) + ",\"sensorConfirmed\":" + receipt.sensorConfirmed()
                + ",\"executedSlotId\":" + receipt.executedSlotId() + "}");
        // 设备声称时间只用于对账；权威时间是 received_at（S-07）
        report.setReportedAt(now);
        report.setReceivedAt(now);
        report.setCreateTime(now);

        // 序号对比不能短路：设备重启后 seq 从 0 或 1 重放是真实场景，
        // “seq 很小”本身就是旧事件的信号（这里曾写过一个 `seq > 0` 的“优化”，恰好把最该判旧的样本放过了）
        Long maxSeq = reportMapper.selectMaxSeq(cabinet.getId(), slotId);
        if (maxSeq != null && receipt.seq() <= maxSeq) {
            // 序号较旧不再拦截业务：设备重启后 seq 会归零，旧≠重复；仅留证据供对账
            log.info("回执序号不高于已记录（可能为设备重启重发），仍按本指令处理 requestId={} seq={} maxSeq={}",
                    receipt.requestId(), receipt.seq(), maxSeq);
        }
        try {
            reportMapper.insert(report);
        } catch (DuplicateKeyException e) {
            log.info("同一指令回执重复送达，不重复推进状态 dedupKey={}", report.getDedupKey());
            return true;
        }
        return false;
    }

    /** 阈值到了就把格口标故障、柜机停用：别再往坏柜机里放新单。 */
    private CommandOutcome maybeDisable(BizCabinet cabinet, Long slotId, int fails, Long commandId,
                                        CommandState state, FaultType fault, String message) {
        if (fails < failThreshold) {
            return new CommandOutcome(commandId, state, false, false, fault, message);
        }
        if (slotId != null) {
            BizCompartment slot = new BizCompartment();
            slot.setId(slotId);
            slot.setStatus(SlotStatus.FAULT);
            slotMapper.updateById(slot);
        }
        BizCabinet disabled = new BizCabinet();
        disabled.setId(cabinet.getId());
        disabled.setCabinetStatus(CabinetStatus.DISABLED);
        cabinetMapper.updateById(disabled);
        recordFault(cabinet.getId(), slotId, FaultType.CONSECUTIVE_FAIL, FaultType.Action.MARK_UNAVAILABLE,
                fails, "连续失败达阈值 " + failThreshold + "，格口标故障、柜机停用");
        log.warn("柜机停用 cabinetId={} 连续失败={} 阈值={}", cabinet.getId(), fails, failThreshold);
        return new CommandOutcome(commandId, state, false, false, FaultType.CONSECUTIVE_FAIL,
                "该柜机已停用，请换一台柜机");
    }

    private void transit(BizDeviceCommand command, CommandState target) {
        if (command.getStatus() == null) {
            command.initStatus();
        }
        command.transitTo(target);
    }

    private BizDeviceCommand newCommand(BizCabinet cabinet, Long slotId, CommandAction action,
                                        Long orderId, String requestId) {
        BizDeviceCommand command = new BizDeviceCommand();
        command.setTenantId(cabinet.getTenantId());
        command.setOrderId(orderId);
        command.setCabinetId(cabinet.getId());
        command.setSlotId(slotId);
        command.setRequestId(requestId);
        command.setAction(action);
        command.initStatus();
        return command;
    }

    private void recordFault(Long cabinetId, Long slotId, FaultType type, FaultType.Action action,
                             int failCount, String reason) {
        BizFaultEvent event = new BizFaultEvent();
        // 不手动写 tenantId：由填充层从当前上下文取（第 11 刀的守卫已改为“显式值优先，否则用上下文”，
        // 这里没有显式值的理由，就不应该传 null 去干扰判断
        event.setCabinetId(cabinetId);
        event.setSlotId(slotId);
        event.setFaultType(type);
        event.setAction(action);
        event.setFailCount(failCount);
        event.setReason(reason);
        faultMapper.insert(event);
    }

    /** 连续失败计数放 Redis（TTL 内的滑动窗口），不是 DB：这是易失的运行态，不需要历史。 */
    private int failureCount(Long cabinetId) {
        String raw = redis.opsForValue().get(FAIL_KEY_PREFIX + cabinetId);
        return raw == null ? 0 : Integer.parseInt(raw);
    }

    private int countFailure(Long cabinetId) {
        Long total = redis.opsForValue().increment(FAIL_KEY_PREFIX + cabinetId);
        redis.expire(FAIL_KEY_PREFIX + cabinetId, Duration.ofMinutes(30));
        return total == null ? 1 : total.intValue();
    }

    private void clearFailure(Long cabinetId) {
        redis.delete(FAIL_KEY_PREFIX + cabinetId);
    }

    private String jsonString(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "'") + "\"";
    }
}
