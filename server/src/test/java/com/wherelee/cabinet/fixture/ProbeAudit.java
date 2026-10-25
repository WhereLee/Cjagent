package com.wherelee.cabinet.fixture;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("probe_audit")
public class ProbeAudit extends BaseEntity {

    private String name;
}
