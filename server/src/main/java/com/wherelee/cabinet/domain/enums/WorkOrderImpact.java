package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

/** 影响范围：一格、整柜、还是账户级。 */
public enum WorkOrderImpact {

    /** 单个格口。 */
    SINGLE,
    /** 整柜或多格口。 */
    CABINET,
    /** 账户/资金级。 */
    ACCOUNT;

    public static WorkOrderImpact of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
