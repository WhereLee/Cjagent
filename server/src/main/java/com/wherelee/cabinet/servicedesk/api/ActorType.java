package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

/** 工单事件的执行者类型：把 AI 动作、人工动作和系统动作分开记。 */
public enum ActorType {

    USER,
    AI,
    OPERATOR,
    SYSTEM;

    public static ActorType of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
