package com.wherelee.cabinet.servicedesk.domain;
import com.wherelee.cabinet.domain.entity.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.servicedesk.api.WorkOrderCategory;
import com.wherelee.cabinet.servicedesk.api.WorkOrderImpact;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntent;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderUrgency;
import lombok.Getter;
import lombok.Setter;

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
    private Integer version;
    private Boolean enabled;
}
