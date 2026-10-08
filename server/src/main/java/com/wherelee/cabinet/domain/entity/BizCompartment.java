package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import lombok.Getter;
import lombok.Setter;

/**
 * 格口。
 *
 * <p>{@code currentOrderId} 与 {@code status} 是同一事实的两种记法，因此必须一致：
 * 空闲时 currentOrderId 为空、占用时非空。<b>不变量由服务层维护，由测试断言</b>
 * （{@code SlotOrderInvariantsTest}），单靠数据库约束做不到跨行一致。
 *
 * <p>{@code version} 走 MyBatis-Plus 乐观锁；注意它要求
 * {@code OptimisticLockerInnerInterceptor} 已注册，否则这就是个不会自己动的普通列。
 */
@Getter
@Setter
@TableName("biz_compartment")
public class BizCompartment extends BaseEntity {

    private Long cabinetId;
    private String slotNo;
    private SizeType sizeType;
    private SlotStatus status;
    private Long currentOrderId;

    @Version
    private Integer version;
}
