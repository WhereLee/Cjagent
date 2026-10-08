package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.domain.enums.CommandState;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 设备指令（下发 → 回执 的一次往返）。
 *
 * <p>{@code requestId} 是全局幂等键（DDL 上是 {@code uk_cmd_request_id}）：
 * <b>重试不改这个值</b>。设备侧重复执行、网络重发、用户重复点击，都必须在
 * "同一次逻辑操作"的语义下被合并——如果每次重试都换新 ID，幂等就等于没做。
 *
 * <p>状态只能通过 {@link #transitTo} 改，理由与寄存单一致：让非法迁移在代码结构上不存在。
 */
@Getter
@Setter
@TableName("biz_device_command")
public class BizDeviceCommand extends BaseEntity {

    /** 关联寄存单；运维/强制开柜可以为空（不是每张单都起源于用户）。 */
    private Long orderId;

    private Long cabinetId;

    private Long slotId;

    private String requestId;

    private CommandAction action;

    private CommandState status;

    /** 重试次数。达阈值后是否放弃由业务判断（第 13 刀调度），状态机本身不判。 */
    private Integer retryCount;

    /** 人工发起时留痕：强制开柜必须能查到是谁。 */
    private Long operatorId;

    private LocalDateTime sentAt;

    private LocalDateTime ackedAt;

    /** 失败原因要能被清空（重试成功后不留旧错误），所以 ALWAYS。 */
    @TableField(value = "last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    @Version
    private Integer version;

    /** 状态迁移唯一入口。 */
    public void transitTo(CommandState target) {
        if (status == null) {
            throw new IllegalStateException("指令状态为空，数据异常: " + requestId);
        }
        if (!status.canTransitTo(target)) {
            throw new IllegalStateException("指令 " + requestId + " 不允许从 " + status + " 迁移到 " + target);
        }
        this.status = target;
    }

    public void initStatus() {
        this.status = CommandState.PENDING;
        this.retryCount = 0;
        this.version = 0;
    }
}
