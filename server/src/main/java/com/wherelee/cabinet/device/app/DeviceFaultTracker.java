package com.wherelee.cabinet.device.app;

import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.device.api.CommandState;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.device.domain.BizFaultEvent;
import com.wherelee.cabinet.device.infra.BizFaultEventMapper;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SlotPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 设备故障台账与“连续失败即停用”的判定。
 *
 * <p>为什么从指令状态机里拆出来：状态机回答“这条指令现在是什么状态”，
 * 而“失败了几次、要不要摘柜机、台账怎么写”是运行态的计数与运营动作。
 * 两者混在一起时，读状态机必须同时读懂 Redis 计数窗口与台账字段。
 */
@Component
public class DeviceFaultTracker {

    private static final Logger log = LoggerFactory.getLogger(DeviceFaultTracker.class);

    private static final String FAIL_KEY_PREFIX = "cab:dev:fail:";

    private final BizFaultEventMapper faultMapper;
    private final StringRedisTemplate redis;
    private final SlotPort slotStates;

    /** 连续失败到第几次就摘柜机（用户定的阈值，不散落在业务分支里）。 */
    @Value("${cabinet.device.fail-threshold:3}")
    private int failThreshold;

    public DeviceFaultTracker(BizFaultEventMapper faultMapper, StringRedisTemplate redis,
                              SlotPort slotStates) {
        this.faultMapper = faultMapper;
        this.redis = redis;
        this.slotStates = slotStates;
    }

    /** 阈值到了就把格口标故障、柜机停用：别再往坏柜机里放新单。 */
    public CommandOutcome maybeDisable(CabinetSnapshot cabinet, Long slotId, int fails, Long commandId,
                                       CommandState state, FaultType fault, String message) {
        if (fails < failThreshold) {
            return new CommandOutcome(commandId, state, false, false, fault, message);
        }
        if (slotId != null) {
            slotStates.markSlotFault(slotId);
        }
        slotStates.disableCabinet(cabinet.id());
        recordFault(cabinet.id(), slotId, FaultType.CONSECUTIVE_FAIL, FaultType.Action.MARK_UNAVAILABLE,
                fails, "连续失败达阈值 " + failThreshold + "，格口标故障、柜机停用");
        log.warn("柜机停用 cabinetId={} 连续失败={} 阈值={}", cabinet.id(), fails, failThreshold);
        return new CommandOutcome(commandId, state, false, false, FaultType.CONSECUTIVE_FAIL,
                "该柜机已停用，请换一台柜机");
    }

    public void recordFault(Long cabinetId, Long slotId, FaultType type, FaultType.Action action,
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
    public int failureCount(Long cabinetId) {
        String raw = redis.opsForValue().get(FAIL_KEY_PREFIX + cabinetId);
        return raw == null ? 0 : Integer.parseInt(raw);
    }

    public int countFailure(Long cabinetId) {
        Long total = redis.opsForValue().increment(FAIL_KEY_PREFIX + cabinetId);
        redis.expire(FAIL_KEY_PREFIX + cabinetId, Duration.ofMinutes(30));
        return total == null ? 1 : total.intValue();
    }

    public void clearFailure(Long cabinetId) {
        redis.delete(FAIL_KEY_PREFIX + cabinetId);
    }
}
