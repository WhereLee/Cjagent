package com.wherelee.cabinet.application.workorder;

import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.infrastructure.mapper.SysUserMapper;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 责任组 → 值班人。
 *
 * <p>不做排班表：储物柜团队规模小，排班表会变成“谁忘了改表谁背锅”。这里用
 * <b>“组内启用账号 + 轮转计数”</b>——同一个组的事轮流落到每个人头上，Redis 计数保证多实例下也不重复派给同一人。
 *
 * <p>组到角色沿用现有 RBAC：ONLINE→online_ops，FIELD/FIELD_OPS→field_ops。
 * CUSTOMER_SERVICE 没有对应角色（人工分拣不是某个岗位的固定职责），返回 null，
 * 让单子留在队列里等人工分拣，而不是硬塞给在线业务员。
 */
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

    /**
     * 取该组当前值班人：组内启用账号按轮转计数依次分配。
     *
     * @return null 表示该组没有可派的人（或该组不做自动派单），调用方保持未指派
     */
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
            // Redis 挂了不阻断派单：退回组内第一个账号，至少有人被指向
            log.warn("值班轮转计数失败，退回组内首个账号 group={} role={}", assigneeGroup, roleCode, e);
            index = 0;
        }
        return candidates.get(index);
    }

    /** 测试或运维重置轮转（不对外暴露接口）。 */
    public void resetRotation(String assigneeGroup) {
        String roleCode = GROUP_ROLE.get(assigneeGroup);
        if (roleCode != null) {
            redisson.getAtomicLong("wo:duty:" + TenantContext.current() + ":" + roleCode).delete();
        }
    }
}
