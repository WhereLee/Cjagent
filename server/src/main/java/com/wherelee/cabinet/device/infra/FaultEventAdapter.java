package com.wherelee.cabinet.device.infra;

import com.wherelee.cabinet.device.api.FaultPort;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.device.domain.BizFaultEvent;
import org.springframework.stereotype.Component;

@Component
public class FaultEventAdapter implements FaultPort {

    private final BizFaultEventMapper faultMapper;

    public FaultEventAdapter(BizFaultEventMapper faultMapper) {
        this.faultMapper = faultMapper;
    }

    @Override
    public void record(Long cabinetId, Long slotId, FaultType.Action action, int failCount, String reason) {
        BizFaultEvent event = new BizFaultEvent();
        event.setCabinetId(cabinetId);
        event.setSlotId(slotId);
        event.setFaultType(FaultType.DOOR_NOT_CLOSED);
        event.setAction(action);
        event.setFailCount(failCount);
        event.setReason(reason);
        faultMapper.insert(event);
    }
}
