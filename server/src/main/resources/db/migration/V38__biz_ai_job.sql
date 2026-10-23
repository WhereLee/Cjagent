-- B 端 AI 作业表：长文本生成不再占请求线程。
--
-- 要解决的问题：`/diagnose`、`/ask`、`/ai-brief` 三个端点都是"调模型 → 同步返回几千字"。
-- 单次实测 9.7~19.1 秒（22/23 刀真模型联调记录），而 Tomcat 线程就这么被占着。
-- 改成：提交 → 立刻返回作业号 → 作业在后台跑 → 前端拿作业号轮询结果。
--
-- 为什么是独立表，不塞进 biz_outbox 或 biz_delay_task：
--   1. 它要被**前端按作业号读**。那两张表的行是给机器消费的（谁认领、投出去没有），
--      给它们加“给前端读”的查询等于让一张表同时承担两种读者。
--   2. 它要**存结果**（几千字的报告/答案/简报）。outbox 的 payload 是“投递需要的输入”，不是产出。
--   3. 状态机不同：outbox 我故意没有 RUNNING（投递幂等不需要）；delay_task 有 RUNNING 但没有结果列。
--
-- 去重（防双击）不用时间窗口，用 active_flag：
--   作业没跑完时写 1、DONE/FAILED 时置 NULL。唯一键带上它之后，
--   “同一人 + 同一类型 + 同一请求键”在**未完成**状态下只能有一条；
--   一旦完成（NULL），同内容可以再来一次——那通常是真的想再问。
--   MySQL 唯一索引对含 NULL 的行不判重，这正是这里要的语义（与 biz_work_order.active_flag 同款）。
--
-- 执行：主路径是扫表认领（租约过期可被接管），MQ 只做“立刻开工”的唤醒。
-- 所以 MQ 消息丢了作业也不会永久卡在 SUBMITTED。

CREATE TABLE IF NOT EXISTS biz_ai_job
(
    id              BIGINT       NOT NULL COMMENT '雪花 ID',
    tenant_id       BIGINT       NOT NULL COMMENT '运营商 ID',
    job_type        VARCHAR(16)  NOT NULL COMMENT 'DIAGNOSE 运维诊断 / ASK 运维问答 / BRIEF 工单简报',
    request_key     VARCHAR(128) NOT NULL COMMENT '去重键：无入参用固定串，问答用问题文本，简报用工单 ID',
    requester_id    BIGINT       NOT NULL COMMENT '提交人：AI 产出仅对提交人可见（copilot 形态）',
    payload         VARCHAR(2048) NULL COMMENT '入参原文（如问题），仅供执行时使用',
    status          VARCHAR(16)  NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED / RUNNING / DONE / FAILED',
    -- 1 = 还没跑完；DONE/FAILED 置 NULL（NULL 可重复，故同内容可再做一次）
    active_flag     TINYINT      NULL COMMENT '1=未完成；终态置 NULL',
    result          MEDIUMTEXT   NULL COMMENT 'AI 产出（报告/答案/简报），前端轮询读取',
    last_error      VARCHAR(255) NULL COMMENT '最近失败原因',
    attempt         INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数；用尽转 FAILED',
    lease_owner     VARCHAR(64)  NULL COMMENT '持有者（实例 ID + 随机串）',
    lease_expire_at DATETIME(3)  NULL COMMENT '租约到期；过期可被接管（执行进程被 kill 不会永久卡死）',
    create_time     DATETIME(3)  NOT NULL,
    update_time     DATETIME(3)  NOT NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 防双击的裁判：未完成状态下同一请求只能有一条
    UNIQUE KEY uk_ai_job_active (tenant_id, job_type, request_key, requester_id, active_flag),
    -- 执行器扫描口径：待开工，或租约已过期需要接管
    KEY idx_ai_job_due (status, lease_expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='B 端 AI 作业：提交拿作业号，后台跑，前端轮询';
