-- ============================================================================
-- V31：SLA 双时限的暂停/恢复（批次 4）
--
-- 新增两列而不是另建工作日历表：计划里只支持“暂停/恢复”两个动作，
-- 暂停开始时刻 + 累计暂停分钟数足够算“暂停时长不计入 SLA”，
-- 建节假日表会把这件事变成排班系统。
--
-- 同时把种子规则的分钟数清空：SLA 数值统一由 cabinet.work-order.sla 按优先级取
-- （P1 10min/2h、P2 30min/8h、P3 2h/24h、P4 8h/72h），改档位只改配置。
-- 规则表里的分钟数保留为“按现象定制”的覆盖口：为空才落到优先级策略。
-- 优先级不清空：门未关且有物等个别现象需要压过矩阵（用户定的 10 分钟）。
-- ============================================================================

ALTER TABLE biz_work_order
    ADD COLUMN sla_paused_at DATETIME(3) NULL AFTER first_response_at,
    ADD COLUMN sla_paused_minutes INT NOT NULL DEFAULT 0 AFTER sla_paused_at;

-- 分钟数与优先级改为可空：空 = 落到策略（优先级按矩阵、SLA 按配置），
-- 非空 = 按现象定制。V29 建表时写成了 NOT NULL，这里改回来。
ALTER TABLE biz_triage_rule
    MODIFY COLUMN priority VARCHAR(8) NULL COMMENT 'P1-P4；空=按影响×紧急度矩阵计算',
    MODIFY COLUMN response_minutes INT NULL COMMENT '首次响应时限（分钟）；空=按优先级配置',
    MODIFY COLUMN resolve_minutes INT NULL COMMENT '解决时限（分钟）；空=按优先级配置';

UPDATE biz_triage_rule
   SET response_minutes = NULL,
       resolve_minutes = NULL
 WHERE tenant_id = 1
   AND version = 1;
