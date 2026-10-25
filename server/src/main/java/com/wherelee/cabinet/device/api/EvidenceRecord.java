package com.wherelee.cabinet.device.api;

public record EvidenceRecord(Long tenantId, Long cabinetId, Long compartmentId,
                             String moment, String source, String presence, String note) {
}
