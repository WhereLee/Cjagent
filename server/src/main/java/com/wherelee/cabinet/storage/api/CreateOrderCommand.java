package com.wherelee.cabinet.storage.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOrderCommand(

        @NotBlank(message = "requestId 不能为空")
        @Size(max = 64, message = "requestId 过长")
        String requestId,

        @NotBlank(message = "柜机编号不能为空")
        @Size(max = 32, message = "柜机编号过长")
        String cabinetNo,

        @NotBlank(message = "尺寸不能为空")
        @Pattern(regexp = "(?i)LARGE|MEDIUM|SMALL", message = "尺寸只能是 LARGE/MEDIUM/SMALL")
        String sizeType) {
}
