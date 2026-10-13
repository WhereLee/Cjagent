package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.EvidenceSource;
import com.wherelee.cabinet.domain.enums.Presence;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 柜内物检证据留底（一次观测一条，<b>只增不改</b>）。
 *
 * <p>与格口上的 {@code presence} 分工：格口存"当前结论 + 时刻"（分配与结束判定读它），
 * 这张表存"每一次观测"（争议与误报率读它）。两份不是冗余：判定要的是现值，
 * 复盘要的是时间线，而时间线一旦被就地修改就不再是证据。
 *
 * <p>{@code photoRef} 只存引用不存图片：本项目不实现拍照与图片存储（设备边界，
 * 见 docs/门态与物品争议设计.md §9）。留这个字段是为了让"AI 的输入是什么"在数据模型里先成立，
 * 真接设备时换成对象存储的 key，判定逻辑一行不用改。
 */
@Getter
@Setter
@TableName("biz_item_evidence")
public class BizItemEvidence extends BaseEntity {

    private Long orderId;
    private Long cabinetId;
    private Long compartmentId;
    private EvidenceSource source;
    /** 三值结论：PRESENT / ABSENT / UNKNOWN。把 UNKNOWN 折成有或无就是在最该谨慎的地方造假。 */
    private Presence presence;
    /** AI 置信度（0~1），只有 source=AI 时有值；低置信度是"无法判断"的来源之一。 */
    private BigDecimal confidence;
    private String photoRef;
    /**
     * 采集点（19C）。同一个格口在四个时刻都会各留一条：没有这一列，四条链的证据会混成一堆，
     * 事后回答不了“关门那一刻到底看到什么”。旧记录为 NULL（当时没这个语义，不回填）。
     */
    private Moment moment;
    /** AI 简单标签：给业务员读的一行字（“像是黑色背包”）。与置信度不同，它不要求模型多有把握。 */
    private String aiLabel;
    /** 人看的说明：判误报的依据、复审结论等。客服与运维读的是这句，不是枚举名。 */
    private String note;

    /** 证据采集点（用户原话：开门一张、关门一张、5 分钟检测到有物一张、他人代关后一张）。 */
    public enum Moment {
        /** 格口刚打开（此时柜内应为空基准）。 */
        DOOR_OPENED,
        /** 格口关上（带物检结论，是遗留判定的主凭据）。 */
        DOOR_CLOSED,
        /** 看管期间的定时检测（门未关阶梯上每发一次提醒做一次）。 */
        WATCH_DETECT,
        /** 另一位用户代关之后的检测（用于与代关前的记录对比）。 */
        HELPER_CLOSED,
        /** 物主自助取回后的复检（第 21 刀）：确认柜内已空才放格子回可售。 */
        RETRIEVED_CHECK
    }
}
