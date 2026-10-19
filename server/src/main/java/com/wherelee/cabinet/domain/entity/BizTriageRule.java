package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.WorkOrderCategory;
import com.wherelee.cabinet.domain.enums.WorkOrderImpact;
import com.wherelee.cabinet.domain.enums.WorkOrderIntent;
import com.wherelee.cabinet.domain.enums.WorkOrderPriority;
import com.wherelee.cabinet.domain.enums.WorkOrderUrgency;
import lombok.Getter;
import lombok.Setter;

/** 分诊规则：把用户现象映射成处置分类、队列、优先级与 SLA。 */
@Getter
@Setter
@TableName("biz_triage_rule")
public class BizTriageRule extends BaseEntity {

    private String ruleCode;
    private WorkOrderIntent intent;
    private WorkOrderCategory category;
    private String queueSide;
    private String assigneeGroup;
    private WorkOrderPriority priority;
    private WorkOrderImpact impact;
    private WorkOrderUrgency urgency;
    private Integer responseMinutes;
    private Integer resolveMinutes;
    private String conditionJson;
    /** 规则版本，不是乐观锁。 */
    private Integer version;
    private Boolean enabled;
}
