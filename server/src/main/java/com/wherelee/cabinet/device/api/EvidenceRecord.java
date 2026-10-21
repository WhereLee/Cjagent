package com.wherelee.cabinet.device.api;

/**
 * 一条待落的证据。
 *
 * <p>用字符串而不是实体：采集点在格口状态机那边，按名字传过来就够了；
 * 一旦把实体交出去，写证据的人就会顺手改别的列。
 */
public record EvidenceRecord(Long tenantId, Long cabinetId, Long compartmentId,
                             String moment, String source, String presence, String note) {
}
