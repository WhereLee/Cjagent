package com.wherelee.cabinet.interfaces.admin.auth.dto;

import java.util.List;

/**
 * 当前登录账号信息：前端启动时拉一次，用于渲染菜单与按钮。
 *
 * <p>{@code authorities} 直接给成品权限串（角色带 {@code ROLE_} 前缀、权限点原样编码），
 * 前端按编码控制显示。<b>前端隐藏按钮只是体验</b>，服务端仍然逐项校验——
 * 前端把按钮藏起来不等于接口安全。
 */
public record CurrentAccountView(Long userId,
                                 String username,
                                 Long tenantId,
                                 String end,
                                 List<String> authorities) {
}
