package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

/**
 * 柜内证据来源（{@code biz_item_evidence.source}）。
 *
 * <p>三类都要留底，因为**争议判定看的是"谁在什么时候说了什么"**：红外说有、AI 看图说没有，
 * 这两条记录同时存在才谈得上"设备误报"，也才谈得上免除争议期间的费用。
 * 只留最后一个结论，纠纷就只能各说各话。
 */
public enum EvidenceSource {

    /** 格口红外/光电物检（关门时顺带观测，正常结束就靠它） */
    INFRARED,
    /** 设备探测（现场或远程结束前的一次现读） */
    DEVICE_PROBE,
    /** AI 看图复审：输入是最近一次关门照片 + 该格口的空格基准照片 */
    AI;

    public static EvidenceSource of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
