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

@Component
public class DeviceFaultTracker {

    private static final Logger log = LoggerFactory.getLogger(DeviceFaultTracker.class);

    private static final String FAIL_KEY_PREFIX = "cab:dev:fail:";

    private final BizFaultEventMapper faultMapper;
    private final StringRedisTemplate redis;
    private final SlotPort slotStates;

    @Value("${cabinet.device.fail-threshold:3}")
    private int failThreshold;

    public DeviceFaultTracker(BizFaultEventMapper faultMapper, StringRedisTemplate redis,
                              SlotPort slotStates) {
        this.faultMapper = faultMapper;
        this.redis = redis;
        this.slotStates = slotStates;
    }

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
        event.setCabinetId(cabinetId);
        event.setSlotId(slotId);
        event.setFaultType(type);
        event.setAction(action);
        event.setFailCount(failCount);
        event.setReason(reason);
        faultMapper.insert(event);
    }

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
