package com.wherelee.cabinet.device.infra;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.device.api.EvidenceMoment;
import com.wherelee.cabinet.device.api.EvidencePort;
import com.wherelee.cabinet.device.api.EvidenceRecord;
import com.wherelee.cabinet.device.api.EvidenceSnapshot;
import com.wherelee.cabinet.device.api.EvidenceSource;
import com.wherelee.cabinet.device.domain.BizItemEvidence;
import com.wherelee.cabinet.locker.api.Presence;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link EvidencePort} 的进程内实现。
 *
 * <p>两个查询的排序刻意不同，沿用原来的调用方预期：格口时间线新→旧（客服先看最近一次），
 * 一张单的证据旧→新（按发生顺序读才看得懂）。换顺序会悄悄改掉页面语义。
 */
@Component
public class EvidenceQueryAdapter implements EvidencePort {

    private final BizItemEvidenceMapper evidence;

    public EvidenceQueryAdapter(BizItemEvidenceMapper evidence) {
        this.evidence = evidence;
    }

    @Override
    public List<EvidenceSnapshot> timelineOfCompartment(Long compartmentId, int limit) {
        if (compartmentId == null) {
            return List.of();
        }
        return evidence.timelineOf(compartmentId, clamp(limit)).stream()
                .map(EvidenceQueryAdapter::toSnapshot).toList();
    }

    @Override
    public List<EvidenceSnapshot> byOrder(Long orderId, int limit) {
        if (orderId == null) {
            return List.of();
        }
        return evidence.selectList(Wrappers.<BizItemEvidence>lambdaQuery()
                        .eq(BizItemEvidence::getOrderId, orderId)
                        .orderByAsc(BizItemEvidence::getCreateTime)
                        .last("limit " + clamp(limit)))
                .stream().map(EvidenceQueryAdapter::toSnapshot).toList();
    }

    @Override
    public List<EvidenceSnapshot> byCompartment(Long compartmentId, int limit) {
        if (compartmentId == null) {
            return List.of();
        }
        return evidence.selectList(Wrappers.<BizItemEvidence>lambdaQuery()
                        .eq(BizItemEvidence::getCompartmentId, compartmentId)
                        .orderByAsc(BizItemEvidence::getCreateTime)
                        .last("limit " + clamp(limit)))
                .stream().map(EvidenceQueryAdapter::toSnapshot).toList();
    }

    @Override
    public void record(EvidenceRecord record) {
        BizItemEvidence evidenceRow = new BizItemEvidence();
        evidenceRow.setTenantId(record.tenantId());
        evidenceRow.setCabinetId(record.cabinetId());
        evidenceRow.setCompartmentId(record.compartmentId());
        // 三值结论直接带上：测不准就是 UNKNOWN，不折成“没东西”
        evidenceRow.setPresence(record.presence() == null ? Presence.UNKNOWN : Presence.valueOf(record.presence()));
        evidenceRow.setSource(record.source() == null ? EvidenceSource.DEVICE_PROBE : EvidenceSource.valueOf(record.source()));
        evidenceRow.setMoment(record.moment() == null ? null : EvidenceMoment.valueOf(record.moment()));
        evidenceRow.setNote(record.note());
        evidenceRow.setCreateTime(java.time.LocalDateTime.now());
        evidence.insert(evidenceRow);
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    private static EvidenceSnapshot toSnapshot(BizItemEvidence e) {
        return new EvidenceSnapshot(
                e.getMoment() == null ? null : e.getMoment().name(),
                e.getSource() == null ? null : e.getSource().name(),
                e.getPresence() == null ? null : e.getPresence().name(),
                e.getConfidence(), e.getAiLabel(), e.getPhotoRef(), e.getNote(), e.getCreateTime());
    }
}
