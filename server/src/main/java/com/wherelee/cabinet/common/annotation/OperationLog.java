package com.wherelee.cabinet.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计：标在 Controller 方法上，切面负责落库（成功与失败都留痕）。
 *
 * <p>两条必须知道的语义：
 * <ul>
 *   <li><b>失败也要记</b>：审计的价值大半在"谁在什么时候试了什么但没成"，
 *       所以切面在异常分支同样写记录（带 error 摘要与 success=0）；</li>
 *   <li><b>入参按字段名脱敏后截断</b>（见 {@code MaskUtils}）：不加这条，
 *       审计表会变成全站明文密码与手机号最集中的地方。</li>
 * </ul>
 *
 * <p>切面用 {@code REQUIRES_NEW} 独立事务写日志：业务回滚不该把"有人试过"这条事实一起抹掉。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OperationLog {

    /** 业务模块，如 tenant / auth / rider。 */
    String module();

    /** 操作描述，如 后台登录、创建租户。 */
    String operation();

    /** 是否记录入参摘要（默认记，会脱敏+截断）。涉及敏感载荷时可关掉。 */
    boolean saveParams() default true;
}
