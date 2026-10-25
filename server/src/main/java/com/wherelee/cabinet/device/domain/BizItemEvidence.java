package com.wherelee.cabinet.device.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.device.api.EvidenceMoment;
import com.wherelee.cabinet.device.api.EvidenceSource;
import com.wherelee.cabinet.domain.entity.BaseEntity;
import com.wherelee.cabinet.locker.api.Presence;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@TableName("biz_item_evidence")
public class BizItemEvidence extends BaseEntity {

    private Long orderId;
    private Long cabinetId;
    private Long compartmentId;
    private EvidenceSource source;
    private Presence presence;
    private BigDecimal confidence;
    private String photoRef;
    private EvidenceMoment moment;
    private String aiLabel;
    private String note;

}
