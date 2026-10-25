package com.wherelee.cabinet.servicedesk.app;

import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.infrastructure.mapper.SysUserMapper;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class WorkOrderRoutingService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderRoutingService.class);

    private static final Map<String, String> GROUP_ROLE = Map.of(
            "ONLINE", "online_ops",
            "FIELD", "field_ops",
            "FIELD_OPS", "field_ops");

    private final SysUserMapper users;
    private final RedissonClient redisson;

    public WorkOrderRoutingService(SysUserMapper users, RedissonClient redisson) {
        this.users = users;
        this.redisson = redisson;
    }

    public Long dutyUser(String assigneeGroup) {
        String roleCode = GROUP_ROLE.get(assigneeGroup);
        if (roleCode == null) {
            return null;
        }
        List<Long> candidates = users.selectActiveUserIdsByRole(roleCode);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        int index;
        try {
            long seq = redisson.getAtomicLong("wo:duty:" + TenantContext.current() + ":" + roleCode)
                    .incrementAndGet();
            index = (int) Math.floorMod(seq - 1, candidates.size());
        } catch (RuntimeException e) {
            log.warn("值班轮转计数失败，退回组内首个账号 group={} role={}", assigneeGroup, roleCode, e);
            index = 0;
        }
        return candidates.get(index);
    }

    public void resetRotation(String assigneeGroup) {
        String roleCode = GROUP_ROLE.get(assigneeGroup);
        if (roleCode != null) {
            redisson.getAtomicLong("wo:duty:" + TenantContext.current() + ":" + roleCode).delete();
        }
    }
}
