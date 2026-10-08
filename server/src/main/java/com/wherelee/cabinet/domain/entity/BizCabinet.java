package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.OnlineState;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 柜机。
 *
 * <p>{@code cabinetNo} 全局唯一且不可变：二维码里编码的就是它，改一次等于把现场贴纸全换掉。
 *
 * <p>{@code onlineState} 只是<b>快照</b>：在线判定的权威在 Redis 心跳键 TTL 与 MQTT 遗嘱，
 * 走数据库更新既慢又会在上报高峰把行锁打成热点（见 {@link OnlineState} 的说明）。
 */
@Getter
@Setter
@TableName("biz_cabinet")
public class BizCabinet extends BaseEntity {

    private Long siteId;
    private String cabinetNo;
    private String name;
    private Long modelId;
    private CabinetStatus cabinetStatus;
    private OnlineState onlineState;
    private LocalDateTime lastHeartbeatAt;
}
