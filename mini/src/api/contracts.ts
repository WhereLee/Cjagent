// 手写补齐类型：schema-mini.ts 里的 PickupRequest 是登录态取件的 body（可选 voucherCode），
// 与公开凭码取件的 PickupRequest（PublicPickupController.PickupRequest）撞名被覆盖。
// 字段依据 server/src/main/java/com/wherelee/cabinet/interfaces/mini/storage/PublicPickupController.java。

/** 匿名凭码取件入参（/api/public/storage/pickup）：双因子，都必填 */
export interface PublicPickupRequest {
  voucherCode: string
  /** 手机号后四位（四位数字） */
  phoneTail: string
}
