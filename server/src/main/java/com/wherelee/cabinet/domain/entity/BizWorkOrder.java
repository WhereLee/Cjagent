package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.wherelee.cabinet.domain.enums.WorkOrderStatus;
import com.wherelee.cabinet.domain.enums.WorkOrderType;
import lombok.Getter;
import lombok.Setter;

/**
 * 异常工单（19B）。
 *
 * <p>它存在的唯一理由：19A 之后系统不再靠“拦人”收敛异常，那就必须有人被明确指向这件事。
 * 台账能数出多少格口卡着，但“数得出来”和“有人负责”是两回事。
 *
 * <p>{@code slotNo}、{@code orderNo} 与快照 JSON 都是**开单当时的抄录**，不是关联回查：
 * 用户换了手机号、格口改了编号、点位改了名字之后，这张单还要能回答“当时业务员看到的是什么”。
 * 留痕如果不快照，事后就变成对当前数据的解释，那是假的历史。
 */
@Getter
@Setter
@TableName("biz_work_order")
public class BizWorkOrder extends BaseEntity {

    private String orderNo;
    private Long storageId;
    private Long siteId;
    private Long cabinetId;
    private Long slotId;

    /** 格口编号快照（S03 这种），到场时要念得出口。 */
    private String slotNo;
    private Long customerId;

    private WorkOrderType type;

    /** true = 紧急异常（柜内有物或必须快速到场）。级别与类型分开：同一类型两种级别都成立。 */
    private Boolean urgent;

    private WorkOrderStatus status;

    /** ONLINE 线上业务员队列，FIELD 线下运维队列。 */
    private String queueSide;

    /** 线下工单指回它的线上父单；父单记录“为什么派人”，子单记录“到场做了什么”。 */
    private Long parentId;

    /** 1 = 未关闭（参与唯一索引），关闭时置 null —— 见 V17 注释。 */
    private Integer activeFlag;

    private String title;

    /** 位置、编号、时间、用户电话、检测值的开单快照。 */
    private String snapshotJson;

    private Long assigneeId;
    private String resultNote;
    private java.time.LocalDateTime dueAt;
    private java.time.LocalDateTime claimedAt;
    private java.time.LocalDateTime closedAt;

    @Version
    private Integer version;
}
