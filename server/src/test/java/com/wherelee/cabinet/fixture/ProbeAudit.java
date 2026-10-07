package com.wherelee.cabinet.fixture;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 测试专用实体：验证 BaseEntity 的审计字段是否真的被自动填充。
 *
 * <p>放在测试源码里而不是 main：它是<b>验证手段</b>，不是业务能力，
 * 不应该出现在生产包里被误用。
 */
@Getter
@Setter
@TableName("probe_audit")
public class ProbeAudit extends BaseEntity {

    private String name;
}
