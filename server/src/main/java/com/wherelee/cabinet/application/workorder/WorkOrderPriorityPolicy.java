package com.wherelee.cabinet.application.workorder;

import com.wherelee.cabinet.domain.enums.WorkOrderImpact;
import com.wherelee.cabinet.domain.enums.WorkOrderPriority;
import com.wherelee.cabinet.domain.enums.WorkOrderUrgency;
import org.springframework.stereotype.Component;

/**
 * 优先级策略：影响 × 紧急度 → P1-P4。
 *
 * <p>这张矩阵是“同一现象、不同事实，排序不同”的落点：整柜/账户级影响一律进 P1/P2，
 * 单格问题按紧急度排；紧急度低的一律 P4（可以等，但不能被忘掉）。
 *
 * <p>规则表里的 {@code priority} 非空时优先用它——个别现象（例如门未关且有物：用户定的
 * “10 分钟必须处理”）需要压过矩阵，这条口子留在数据里，不写死在代码里。
 */
@Component
public class WorkOrderPriorityPolicy {

    public WorkOrderPriority of(WorkOrderImpact impact, WorkOrderUrgency urgency) {
        WorkOrderUrgency actual = urgency == null ? WorkOrderUrgency.NORMAL : urgency;
        if (actual == WorkOrderUrgency.LOW) {
            return WorkOrderPriority.P4;
        }
        boolean broad = impact == WorkOrderImpact.CABINET || impact == WorkOrderImpact.ACCOUNT;
        if (actual == WorkOrderUrgency.HIGH) {
            return broad ? WorkOrderPriority.P1 : WorkOrderPriority.P2;
        }
        return broad ? WorkOrderPriority.P2 : WorkOrderPriority.P3;
    }
}
