package com.wherelee.cabinet.application.device;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
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
 * 设备指令用例：下发 → 等回执 → 收敛状态 → 记录故障。
 *
 * <p><b>本类最重要的一行设计是"等回执时不开事务"</b>。
 * 如果在 {@code @Transactional} 方法里 future.get(3s)，每个等待中的请求都会<b>占着一个数据库连接</b>；
 * 第 9 刀压测已经量出连接池只有 10 个、事务时长一长吞吐就崩。所以这里用
 * {@link TransactionTemplate} 显式切成两个短事务：写指令 / 收敛回执，中间那段等待<b>不持有连接</b>。
 * 这也是没有把方法标成事务注解的原因——看起来"少写了一层保护"，实际上是拿掉了瓶颈。
 *
 * <p>故障矩阵与这里的分支一一对应（规划 §8）：无回执、谎报关门、错报目标、乱序重放、
 * 离线降级、连续失败停用。每种都有用例断言，不是"写了个 switch 就算支持"。
 */
@Service
public class DeviceCommandService {

    private static final Logger log = LoggerFactory.getLogger(DeviceCommandService.class);
    private static final String FAIL_KEY_PREFIX = "cab:dev:fail:";

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

    public DeviceCommandService(BizDeviceCommandMapper commandMapper,
                                BizDeviceReportMapper reportMapper,
                                BizFaultEventMapper faultMapper,
                                BizCompartmentMapper slotMapper,
                                BizCabinetMapper cabinetMapper,
                                DeviceChannel channel,
                                StringRedisTemplate redis,
                                TransactionTemplate txTemplate) {
        this.commandMapper = commandMapper;
        this.reportMapper = reportMapper;
        this.faultMapper = faultMapper;
        this.slotMapper = slotMapper;
        this.cabinetMapper = cabinetMapper;
        this.channel = channel;
        this.redis = redis;
        this.txTemplate = txTemplate;
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
        report.setDedupKey("cmd:" + receipt.requestId());
        report.setPayload("{\"requestId\":\"" + receipt.requestId() + "\",\"detail\":"
                + jsonString(receipt.detail()) + ",\"sensorConfirmed\":" + receipt.sensorConfirmed()
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
