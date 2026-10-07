package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 租户（商户/运营商）。
 *
 * <p><b>故意不继承 BaseEntity</b>：BaseEntity 里的 {@code tenantId} 对这张表没有意义
 * （它的 id 本身就是别人的 tenant_id），继承会带来一个永远为空的自引用列。
 * 主键用雪花 ID（{@code assign_id} 全局配置也生效）。
 */
@Getter
@Setter
@TableName("sys_tenant")
public class SysTenant {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantCode;

    private String name;

    /** 1 启用 0 停用。停用租户的账号即使口令正确也不该登录成功。 */
    private Integer status;

    /** 服务到期日；早于今天视为过期。为空表示未设到期。 */
    private LocalDate expireDate;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
