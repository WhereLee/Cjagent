// 分页包（common/api/PageResult<T>）：records 元素类型随接口而变，只能用泛型表达，
// 所以它留在这里手写——这不是补缺口，只是把后端的泛型原样映射过来。
//
// 除此之外这里**不再有手写行类型**。工单、订单、通知、账号、异常概览的行类型
// 以前是被迫手写的：后端 5 个内嵌 record 都叫 View，springdoc 按简单类名生成 schema
// 时互相覆盖，真行类型根本进不了契约。现在每个都改成了唯一名字
// （WorkOrderView / NoticeView / CurrentAccountView / SummaryView / DeadLetterView…），
// 手写补偿层就撤掉了——行类型一律以 schema-admin.ts 的生成物为准。
export interface Paged<T> {
  pageNum?: string
  pageSize?: string
  total?: string
  pages?: string
  records?: T[]
}
