package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.FaultType;
import lombok.Getter;
import lombok.Setter;

/**
 * 故障事件（运维工单与柜机停用的依据）。
 *
 * <p>它是<b>事实记录</b>而不是状态：处置结果落在格口/柜机状态上，这里只留"发生了什么、
 * 当时怎么处置、第几次失败"。所以 {@code failCount} 要写进快照值而不是事后再去数——
 * 事后统计会把"后来恢复了"的现场丢掉，排查时就再也还原不出当时的判断依据。
 */
@Getter
@Setter
@TableName("biz_fault_event")
public class BizFaultEvent extends BaseEntity {

    private Long cabinetId;

    /** 柜机级故障为空。 */
    private Long slotId;

    private FaultType faultType;

    private FaultType.Action action;

    /** 当时的累计失败次数（阈值触发的证据）。 */
    private Integer failCount;

    /** 人工恢复/停用时记录操作人。 */
    private Long operatorId;

    private String reason;
}
