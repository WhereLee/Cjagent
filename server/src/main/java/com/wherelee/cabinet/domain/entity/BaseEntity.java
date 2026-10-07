package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 业务表基类：统一主键、审计字段、逻辑删除与租户字段。
 *
 * <p>为什么要在这里定死：这三列之后存在于**每一张业务表**。等做到第二十个实体再补，
 * 意味着所有表的 DDL、所有已发布接口的返回结构都要返工。所以第一刀就落地。
 *
 * <p>字段由 {@code AuditMetaObjectHandler} 自动填充，业务代码不要手动赋值。
 *
 * <p>只用 @Getter/@Setter，不用 @Data：@Data 在继承场景下默认不生成父类字段的
 * equals/hashCode，容易写出"两个不同租户的记录被判为相等"的隐蔽 bug。
 */
@Getter
@Setter
public abstract class BaseEntity {

    /** 雪花 ID。序列化为 String（见 JacksonConfig），避免前端 JS 精度丢失。 */
    @TableId
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标记：0 正常 / 1 已删。
     *
     * <p>{@code @TableLogic} 只负责查询条件与删除语句，<b>不会</b>在插入时赋值；
     * 必须同时给 {@code fill = INSERT}，否则 {@code strictInsertFill} 不认这个字段
     * （它只填标了填充策略的字段）。只靠数据库列默认值的后果是：插入成功但内存里的
     * 实体 {@code deleted} 仍为 null，业务代码紧接着用这个实体就会拿到空值。
     */
    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer deleted;

    /**
     * 租户 ID。插入时由 MetaObjectHandler 从 TenantContext 填充；
     * <b>查询条件</b>的自动注入由第二刀的 TenantLineInnerInterceptor 负责，
     * 两者缺一都会造成越租读写。
     */
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
}
