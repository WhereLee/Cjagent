package com.wherelee.cabinet.common.mask;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段级出参脱敏：标在 VO 的 String 字段上，序列化时自动遮蔽。
 *
 * <p>为什么用注解而不是让业务代码手工调 {@code MaskUtils.mask(phone)}：手工调用有三个必输的结局
 * ——漏一处就泄露一次、新接口复制粘贴时容易忘、以及"这个字段到底该不该脱"没有就地说明。
 * 注解让规则跟着字段走，review 时一眼可见。
 *
 * <p>注意它<b>只管出参</b>。日志里的脱敏走 {@link MaskUtils}（写审计/入参摘要时用），
 * 两条路都要有，只做出参等于日志里还是明文。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
@JacksonAnnotationsInside
@JsonSerialize(using = JsonMaskSerializer.class)
public @interface JsonMask {

    /** 遮蔽样式。 */
    MaskType value();
}
