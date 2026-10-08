package com.wherelee.cabinet.application.storage.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 占位（下单）命令。
 *
 * <p>{@code requestId} 是客户端生成的幂等键：弱网下"点了没反应再点一次"必然发生，
 * 没有它，用户会拿到两张单、占两个格口。注解上 {@code requireKey = true} 就是为了让
 * 这个字段真的存在——少了它，幂等会静默退化成"按入参摘要兜底"，等于没保护。
 */
public record CreateOrderCommand(

        @NotBlank(message = "requestId 不能为空")
        @Size(max = 64, message = "requestId 过长")
        String requestId,

        @NotBlank(message = "柜机编号不能为空")
        @Size(max = 32, message = "柜机编号过长")
        String cabinetNo,

        @NotBlank(message = "尺寸不能为空")
        @Pattern(regexp = "(?i)LARGE|MEDIUM|SMALL", message = "尺寸只能是 LARGE/MEDIUM/SMALL")
        String sizeType,

        @Min(value = 15, message = "预估时长至少 15 分钟")
        @Max(value = 4320, message = "预估时长最多 3 天")
        int estimateMinutes) {
}
