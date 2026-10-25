package com.wherelee.cabinet.servicedesk.domain;
import com.wherelee.cabinet.domain.entity.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.wherelee.cabinet.domain.enums.CloseReason;
import com.wherelee.cabinet.servicedesk.api.WorkOrderCategory;
import com.wherelee.cabinet.servicedesk.api.WorkOrderImpact;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntent;
import com.wherelee.cabinet.servicedesk.api.WorkOrderKind;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderSource;
import com.wherelee.cabinet.servicedesk.api.WorkOrderStatus;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderUrgency;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("biz_work_order")
public class BizWorkOrder extends BaseEntity {

    private String orderNo;
    private Long storageId;
    private Long siteId;
    private Long cabinetId;
    private Long slotId;

    private String slotNo;
    private Long customerId;

    private WorkOrderKind kind;
    private WorkOrderSource source;
    private WorkOrderType type;
    private WorkOrderIntent intent;
    private WorkOrderCategory category;

    private Boolean urgent;
    private WorkOrderPriority priority;
    private WorkOrderImpact impact;
    private WorkOrderUrgency urgency;

    private WorkOrderStatus status;

    private String queueSide;
    private String assigneeGroup;

    private Long parentId;
    private Long mergedIntoId;

    private Integer activeFlag;

    private String title;

    private String snapshotJson;
    private String handoffJson;

    private Long assigneeId;
    private String resultNote;
    private CloseReason closeReason;
    private java.time.LocalDateTime dueAt;
    private java.time.LocalDateTime responseDueAt;
    private java.time.LocalDateTime resolveDueAt;
    private java.time.LocalDateTime claimedAt;
    private java.time.LocalDateTime firstResponseAt;
    private java.time.LocalDateTime slaPausedAt;
    private Integer slaPausedMinutes;
    private String slaPauseReason;
    private java.time.LocalDateTime closedAt;
    private Integer reopenedCount;

    @Version
    private Integer version;
}
