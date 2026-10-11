-- ============================================================================
-- V20：证据记录的采集时刻与 AI 简单标签（19C）
--
-- 不另起一张“照片表”：`biz_item_evidence` 本来就是“某一刻关于这个格口看到了什么”，
-- 它已经有 presence / confidence / photo_ref。19C 只缺两件事：
--   moment   —— 这条记录是在哪个采集点产生的（开门 / 关门 / 看管检测 / 他人代关后）。
--               没有它，四条链的证据会混成一堆，事后无法回答“关门那一刻看到的是什么”。
--   ai_label —— 用户要的是“AI 打个简单标签，大致说清遗留的是什么东西”（一句人话），
--               它和 confidence 是两回事：置信度是数值，标签是给业务员读的那一行字。
--
-- 关于 photo_ref：本项目是虚拟设备，**不落地任何图片文件**，这一列留空即为“无图”。
-- 有图不代表能判：AI 拿不到输入时一律 UNKNOWN（不硬判无物），这条约束在 13C 已经立过。
-- ============================================================================

ALTER TABLE biz_item_evidence
    ADD COLUMN moment VARCHAR(24) NULL
        COMMENT '采集点：DOOR_OPENED 开门时 / DOOR_CLOSED 关门时 / WATCH_DETECT 看管检测 / HELPER_CLOSED 他人代关后' AFTER photo_ref,
    ADD COLUMN ai_label VARCHAR(120) NULL
        COMMENT 'AI 简单标签（一句话说清大概是什么东西），仅 source=AI 有值' AFTER moment;

-- 旧记录不回填 moment：它们确实没有采集点语义，硬编一个会比留空更假
-- （回填等于凭空给历史安上当时没记的属性，事后复盘会被误导）
CREATE INDEX idx_item_evidence_moment ON biz_item_evidence (compartment_id, moment, create_time);
