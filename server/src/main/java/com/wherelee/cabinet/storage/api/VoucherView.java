package com.wherelee.cabinet.storage.api;

/**
 * 取件凭据视图（第 25 刀：凭据交付闭环）。
 *
 * <p>码只在这一个视图里回显给<b>已登录的本人</b>，且只回显活单——终态单上码已失效
 * （凭码路径 `pickAliveByVoucher` 只认活单，这里若还回显就是自相矛盾）。
 *
 * <p>安全上要讲清一句：登录态可见码<b>不扩大伤害</b>——拿到登录态本来就能直接取件与开柜，
 * 码从来不是防"登录态被盗"的因子；它是"无登录态、人站在柜机前"那条路径的凭据，
 * 那条路径仍守双因子（码 + 手机尾号）与连错锁定。
 *
 * @param locked 该单开柜是否因取件码连错被锁定（true 时前端应给出"重置取件码"的出路）
 */
public record VoucherView(String orderNo,
                          String cabinetNo,
                          String slotNo,
                          String sizeType,
                          String status,
                          String voucherCode,
                          boolean locked) {
}

