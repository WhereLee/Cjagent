package com.wherelee.cabinet.application.storage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import com.wherelee.cabinet.domain.enums.SizeType;

import java.util.Collection;

/**
 * “这个格口现在能不能分给新客户”的<b>唯一定义</b>。
 *
 * <p>为什么要单独一个类而不是各分配器各写一遍：这个判断要出现在六处——悲观锁、CAS、
 * Redisson 三个分配器，CAS 的“还剩多少位”复查，预扣的空闲集合同步，以及空闲集合的定时校准。
 * 各写一遍的后果不是重复代码，而是<b>其中一处漏掉条件就静默多卖</b>：
 * 比如预扣集合里还留着“门开着”的格口，用户就会拿到一个物理上锁不上的格子；
 * 集合里留着“有遗留物”的格口，就等于把别人的行李卖给新客户。
 *
 * <p>第 9~10 刀已经用另一种方式证实过同一件事：四种分配策略各写一套候选逻辑时，
 * 压测结论会自相矛盾，因为测的其实不是并发策略而是四份不同的“可用”定义。
 *
 * <p>三个条件缺一不可：
 * <ul>
 *   <li>{@code status = FREE}：没被任何单占着；</li>
 *   <li>{@code door_open_at IS NULL}：门是关上的——远程结束或取消之后会出现
 *       “FREE 但门开着”，这时这个位置对任何人都还不可用；</li>
 *   <li>{@code anomaly IS NULL}：没有被记为门/物异常（遗留物、测不到、传感器矛盾）。</li>
 * </ul>
 *
 * <p>与 {@link BizCompartment#assignable()} 必须同判据：一个给 SQL 用、一个给 Java 用，
 * 两者不一致时会出现“Java 说可用但查不出来”或反过来，后者更危险（它会放行）。
 */
public final class SlotCandidateQuery {

    private SlotCandidateQuery() {
    }

    /** 某柜机上、尺寸在 {@code acceptable} 内、当前可分配的格口。 */
    public static LambdaQueryWrapper<BizCompartment> assignable(Long cabinetId, Collection<SizeType> acceptable) {
        return assignableBase(cabinetId).in(BizCompartment::getSizeType, acceptable);
    }

    /** 某柜机某尺寸下可分配的格口（预扣集合同步与定时校准用这一条）。 */
    public static LambdaQueryWrapper<BizCompartment> assignable(Long cabinetId, SizeType size) {
        return assignableBase(cabinetId).eq(BizCompartment::getSizeType, size);
    }

    private static LambdaQueryWrapper<BizCompartment> assignableBase(Long cabinetId) {
        return Wrappers.<BizCompartment>lambdaQuery()
                .eq(BizCompartment::getCabinetId, cabinetId)
                .eq(BizCompartment::getStatus, SlotStatus.FREE)
                .isNull(BizCompartment::getDoorOpenAt)
                .isNull(BizCompartment::getAnomaly);
    }
}
