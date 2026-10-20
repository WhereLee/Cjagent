package com.wherelee.cabinet.device.api;

import java.util.List;

/**
 * 物检证据只读端口。
 *
 * <p>工单、运营台、遗留物处置都靠它回答"那一刻柜里到底有没有东西"。
 * 写入仍留在这个服务内部（设备检测点产生证据），读的人只拿快照。
 */
public interface EvidencePort {

    /** 某个格口的证据时间线（新→旧），运营台详情用。 */
    List<EvidenceSnapshot> timelineOfCompartment(Long compartmentId, int limit);

    /** 某张单的证据（旧→新），工单与订单详情用。 */
    List<EvidenceSnapshot> byOrder(Long orderId, int limit);

    /** 某个格口的证据（旧→新），工单详情用（工单只认格口，不保证都在同一张单上）。 */
    List<EvidenceSnapshot> byCompartment(Long compartmentId, int limit);
}
