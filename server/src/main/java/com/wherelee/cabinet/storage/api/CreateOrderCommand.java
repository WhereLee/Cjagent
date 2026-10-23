package com.wherelee.cabinet.storage.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 占位（下单）命令。
 *
 * <p>{@code requestId} 是客户端生成的幂等键：弱网下"点了没反应再点一次"必然发生，
 * 没有它，用户会拿到两张单、占两个格口。注解上 {@code requireKey = true} 就是为了让
 * 这个字段真的存在——少了它，幂等会静默退化成"按入参摘要兜底"，等于没保护。
 *
 * <p>这里曾经还有一个 {@code estimateMinutes}（用户填的"打算存多久"，带 15~4320 的校验）。
 * 19A 把计费改成"预收 4 小时 + 取件按实际时长多退少补"之后它就没有读者了：
 * 建单时故意写 0，出参回显恒 0，前端为了自洽写着"仅用于记录"而实际不留。
 * 记在这里是为了防止重新加回来——"存件应该让用户说存多久"听起来很自然，但计费不看它就没意义。
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
        String sizeType) {
}
