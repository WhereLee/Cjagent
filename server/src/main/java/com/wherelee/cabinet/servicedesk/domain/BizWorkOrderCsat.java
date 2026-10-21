package com.wherelee.cabinet.servicedesk.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 工单满意度：用户点「确认已解决」后的一次性评价。
 *
 * <p>一单一次由 {@code uk_csat_work_order} 兜底，不靠“先查再插”。
 * 它只是事实记录，不参与任何工单状态迁移；评论只落这张表，不写进事件流水。
 */
@Getter
@Setter
@TableName("biz_work_order_csat")
public class BizWorkOrderCsat extends BaseEntity {

    private Long workOrderId;
    private Long customerId;
    /** 1–5 星。 */
    private Integer score;
    /** 选填备注。 */
    private String comment;
}
