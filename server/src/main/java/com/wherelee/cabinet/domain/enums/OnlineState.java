package com.wherelee.cabinet.domain.enums;

/**
 * 在线状态快照。
 *
 * <p><b>权威判定在 Redis（心跳键 TTL + MQTT 遗嘱），这里只是缓存与重启后的回填依据。</b>
 * 原因是"在线与否"必须以秒级过期为准，而走数据库更新既慢又会在高并发上报时把行锁打成热点。
 * 因此这个字段允许滞后，不参与实时判定；要用它做统计可以，做拦截不行。
 */
public enum OnlineState {

    ONLINE,
    OFFLINE,
    /** 尚未收到任何心跳（新接入或刚清库），不能当作在线 */
    UNKNOWN
}
