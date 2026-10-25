package com.wherelee.cabinet.device.api;

import java.util.List;

public interface EvidencePort {

    List<EvidenceSnapshot> timelineOfCompartment(Long compartmentId, int limit);

    List<EvidenceSnapshot> byOrder(Long orderId, int limit);

    List<EvidenceSnapshot> byCompartment(Long compartmentId, int limit);

    void record(EvidenceRecord record);
}
