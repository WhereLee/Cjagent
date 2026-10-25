/**
 * AI 作业产出的形状——**不属于 HTTP 契约**。
 *
 * 批 7 之后三个 AI 端点返回的是作业号，产出经 `/api/admin/ai-jobs/{id}` 以 JSON 取回。
 * 契约层面它是 `unknown`（后端把序列化后的字符串直接存进结果列），所以
 * `schema-admin.ts` 不再产出这些 interface——它们已经从契约里消失了。
 *
 * 但页面要渲染它们，所以在手写一份。形状对应后端的三个 record：
 *   - `OpsReport`  ← `OpsDiagnosePort.OpsReport`
 *   - `OpsAnswer`  ← `OpsDiagnosePort.OpsAnswer`
 *   - `Brief`      ← `WorkOrderBriefPort.Brief`
 *
 * 后端改字段时这里要同步改——这不是"绕过类型检查"，而是承认一件事：
 * **作业产出的结构由后端 record 决定，不由 HTTP 契约决定**。
 */

/** 诊断报告。 */
export interface OpsReport {
  healthSummary?: string
  logFindings?: string[]
  crashArtifacts?: string[]
  advice?: string[]
}

export interface OpsAnswer {
  answer?: string
}

export interface SimilarCase {
  title?: string
  resultNote?: string
  resolvedAt?: string
}

export interface Brief {
  summary?: string
  draftNote?: string
  similarCases?: SimilarCase[]
}
