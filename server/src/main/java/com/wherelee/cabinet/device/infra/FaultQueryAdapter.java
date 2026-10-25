package com.wherelee.cabinet.device.infra;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.device.api.FaultQueryPort;
import com.wherelee.cabinet.device.domain.BizFaultEvent;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FaultQueryAdapter implements FaultQueryPort {

    private final BizFaultEventMapper faultMapper;

    public FaultQueryAdapter(BizFaultEventMapper faultMapper) {
        this.faultMapper = faultMapper;
    }

    @Override
    public List<FaultSnapshot> recent(int limit) {
        return faultMapper.selectList(Wrappers.<BizFaultEvent>lambdaQuery()
                        .orderByDesc(BizFaultEvent::getId)
                        .last("limit " + Math.max(1, Math.min(limit, 200))))
                .stream()
                .map(e -> new FaultSnapshot(e.getSlotId(),
                        e.getAction() == null ? null : e.getAction().name(), e.getCreateTime()))
                .toList();
    }
}
