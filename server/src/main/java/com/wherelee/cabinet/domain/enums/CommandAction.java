package com.wherelee.cabinet.domain.enums;

/**
 * 下发给柜机的动作。
 *
 * <p>{@link #FORCE_OPEN} 单独存在是有原因的：它是唯一"绕过用户意愿"的动作，
 * 必须留痕（谁、什么时候、为什么），后台那一刀会按它做审计与配额。
 * 把它和 {@link #OPEN} 混成一个动作，异常开柜就查不出来。
 */
public enum CommandAction {

    /** 首次开柜（投件） */
    OPEN,
    /** 临时开柜（中途取物），格口仍归该订单占用 */
    OPEN_TEMP,
    /** 关门校验：确认门真的关上了（设备可能谎报） */
    CLOSE_VERIFY,
    /** 强制开柜（运营/应急，必须留操作人） */
    FORCE_OPEN;

    /** 取件方向的开柜：降级时"不可存、可取"（S-09），柜机离线也要尽量放行。 */
    public boolean isRetrieveLike() {
        return this == OPEN_TEMP || this == FORCE_OPEN;
    }

    public static CommandAction of(String value) {
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
