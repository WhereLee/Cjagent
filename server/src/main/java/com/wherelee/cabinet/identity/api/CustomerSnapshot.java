package com.wherelee.cabinet.identity.api;

/** 客户的只读快照：其他服务看客户只需要这两个字段，不需要账号状态与登录信息。 */
public record CustomerSnapshot(Long id, String nickname, String phone) {
}
