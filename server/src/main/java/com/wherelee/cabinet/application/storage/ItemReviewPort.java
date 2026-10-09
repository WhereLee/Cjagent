package com.wherelee.cabinet.application.storage;

import com.wherelee.cabinet.domain.enums.Presence;

import java.math.BigDecimal;

/**
 * AI 看图复审的端口（争议阶梯的第二双眼睛，设计文档 §5）。
 *
 * <p>它是<b>业务侧的端口</b>而不是"某个 HTTP 客户端"：调用方关心的是"这格里到底有没有东西"，
 * 不关心后面是本地模型、云端多模态还是人工回填。换实现不动业务代码——这与设备通道 {@code DeviceChannel}
 * 是同一个手法，也让"AI 挂了怎么办"必须在业务侧回答（答：结论按 UNKNOWN 处理，转人工，
 * <b>绝不因为模型不可用就默认放行</b>）。
 *
 * <p>输入里刻意带上<b>该格口的空格基准照片</b>：光看一张"柜内有阴影"的图，模型无从判断
 * 那是遗留物还是格口内衬、反光。基准图让模型做的是"与空柜比对是否多出东西"，这是这套判定
 * 能成立的前提（用户定的口径）。基准照片必须由业务运维清柜时拍，不能让系统自动采集——
 * 拍下那一刻它并不知道是不是空的，那是循环论证。
 */
public interface ItemReviewPort {

    /**
     * 复审一次格口的柜内情况。
     *
     * @return 三值结论。<b>不允许把"没把握"折成"有"或"无"</b>：折成"无"会把别人的行李卖出去，
     *         折成"有"会把一个空柜永久锁死，两种错的代价都不是一句"取保守值"能承担的
     */
    Review review(Request request);

    /**
     * @param closedPhotoRef   最近一次关门的留底照片引用
     * @param baselinePhotoRef 该格口的空格基准照片引用；为空表示没有基准，模型无从比对
     */
    record Request(Long orderId, Long compartmentId, String slotNo,
                   String closedPhotoRef, String baselinePhotoRef) {
    }

    /**
     * @param presence   PRESENT / ABSENT / UNKNOWN
     * @param confidence 模型置信度（0~1），可空；低置信度是 UNKNOWN 的来源之一
     * @param note       给人看的依据（客服工单直接引用这句）
     */
    record Review(Presence presence, BigDecimal confidence, String note) {

        public boolean indeterminate() {
            return presence == null || presence == Presence.UNKNOWN;
        }

        /** 模型不可用/没有图时的统一退路：当作"不知道"，而不是猜一个。 */
        public static Review unknown(String why) {
            return new Review(Presence.UNKNOWN, null, why);
        }
    }
}
