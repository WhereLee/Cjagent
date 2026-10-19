-- 体检补索引（只加索引，不改表结构与业务语义）。
--
-- biz_notice.pendingCalls：channel + done_at is null + create_time 排序，
-- 且多租户插件会补 tenant_id，所以 tenant_id 必须放最左。
ALTER TABLE biz_notice
    ADD KEY idx_notice_pending (tenant_id, channel, done_at, create_time);

-- biz_delay_task.findDueIdsForUpdateSkip：tenant_id + status + fire_at。
-- 现有 idx_task_due (status, fire_at) 缺 tenant_id，多租户调度只能先按 status 扫再过滤。
ALTER TABLE biz_delay_task
    ADD KEY idx_task_tenant_due (tenant_id, status, fire_at);

-- RUNNING 租约过期接管分支：status = 'RUNNING' and lease_expire_at < now。
ALTER TABLE biz_delay_task
    ADD KEY idx_task_lease (status, lease_expire_at);
