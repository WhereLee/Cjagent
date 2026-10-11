-- ============================================================================
-- V17：工单系统（第 19B 刀，2026-10-11 用户定稿）
--
-- 为什么需要它：19A 把“结束不拦人”定下来之后，门没关、柜内有遗留、开门失败这些事实
-- **不再靠拦人收敛**，只能靠“有人被指向它”。规划里那条老教训还在：台账数得出来、
-- 没人被指向它，就等于没有出口。工单就是把“事实”变成“责任”的那张表。
--
-- 三条结构性约束（不是自觉，是索引）：
--   1. `uk (tenant, type, slot_id, active_flag)`：同一个格口的同一类异常，
--      **未关闭状态下只能有一张工单**。否则巡检每轮都刷一张，队列会被同一件事埋满，
--      而“待处理条数”这个唯一的看板指标就失去意义（active_flag 只在未关闭时=1，
--      关闭后 NULL，MySQL 唯一索引允许多个 NULL，所以历史可以无限留底）。
--   2. 事件表只 INSERT、不 UPDATE：处理过程要能复盘到“谁在什么时候说了什么”。
--   3. `snapshot_json` 存开单当时的位置/编号/时间/用户电话/检测值。
--      这些必须快照而不是回查：用户换了手机号、格口改了归属之后，工单要还能说清
--      “当时业务员看到的是什么”，否则留痕就是假的。
--
-- 父子关系：线上业务员反馈“需要线下运维”时，不是改状态，而是**另发一张线下工单**
-- （`parent_id` 指回父单）。这样“线上做过什么判断”和“线下到场做了什么”各自留痕，
-- 不会互相覆盖成一条谁也说不清的历史。
-- ============================================================================

CREATE TABLE IF NOT EXISTS biz_work_order (
    id              BIGINT        NOT NULL COMMENT '雪花 ID',
    tenant_id       BIGINT        NOT NULL COMMENT '租户',
    order_no        VARCHAR(150)  NULL COMMENT '关联寄存单号（设备类异常可无单）',
    storage_id      BIGINT        NULL COMMENT '关联寄存单 ID',
    site_id         BIGINT        NULL COMMENT '点位（地址归属）',
    cabinet_id      BIGINT        NULL COMMENT '暂存柜编号所在柜机',
    slot_id         BIGINT        NULL COMMENT '格口 ID',
    slot_no         VARCHAR(16)   NULL COMMENT '格口编号快照：业务员到场要念给它听，不能事后被改名带跑',
    customer_id     BIGINT        NULL COMMENT '客户 ID',
    type            VARCHAR(32)   NOT NULL COMMENT 'DOOR_NOT_CLOSED 门未关 / ITEM_LEFT 遗留物 / DEVICE_FAULT 开关门失败 / LONG_RUNNING 超封顶未结束',
    urgent          TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1=紧急异常（柜内有物或需快速到场），0=普通异常',
    status          VARCHAR(24)   NOT NULL COMMENT 'PENDING 待处理 / CLAIMED 已认领 / RESOLVED 已处理 / AUTO_CLOSED 自动转化留底 / CANCELLED 作废',
    queue_side      VARCHAR(16)   NOT NULL DEFAULT 'ONLINE' COMMENT 'ONLINE 线上业务员队列 / FIELD 线下运维队列',
    parent_id       BIGINT        NULL COMMENT '线下工单指回它的线上父单',
    active_flag     TINYINT       NULL COMMENT '1=未关闭（进唯一索引）；关闭后置 NULL 让历史可无限留底',
    title           VARCHAR(120)  NULL COMMENT '一句话说清发生了什么（给队列列表看）',
    snapshot_json   JSON          NULL COMMENT '开单当时的位置/编号/时间/用户电话/检测值快照',
    assignee_id     BIGINT        NULL COMMENT '处理人（sys_user.id），认领时写',
    result_note     VARCHAR(500)  NULL COMMENT '处理结论与事由：免除、派人、暂不处理都要写理由',
    due_at          DATETIME(3)   NULL COMMENT '要求处理时刻（用户定的“10 分钟必须处理”落在这里）',
    claimed_at      DATETIME(3)   NULL,
    closed_at       DATETIME(3)   NULL,
    version         INT           NOT NULL DEFAULT 0 COMMENT '乐观锁：认领必须靠它 + 条件 UPDATE 判胜负',
    create_time     DATETIME(3)   NOT NULL DEFAULT NOW(3),
    update_time     DATETIME(3)   NOT NULL DEFAULT NOW(3) ON UPDATE NOW(3),
    deleted         TINYINT(1)    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_order_open (tenant_id, type, slot_id, active_flag),
    KEY idx_work_order_queue (tenant_id, queue_side, status, urgent, create_time),
    KEY idx_work_order_slot (slot_id, status),
    KEY idx_work_order_order (order_no),
    KEY idx_work_order_parent (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '异常工单（线上/线下两条队列，父子关联）';

CREATE TABLE IF NOT EXISTS biz_work_order_event (
    id              BIGINT        NOT NULL,
    tenant_id       BIGINT        NOT NULL,
    work_order_id   BIGINT        NOT NULL,
    event_type      VARCHAR(32)   NOT NULL COMMENT 'CREATED/CLAIMED/NOTED/DISPATCHED/RESOLVED/AUTO_CLOSED',
    operator_id     BIGINT        NULL COMMENT '系统事件为 NULL（谁做的与做了什么必须分开记）',
    detail          VARCHAR(500)  NULL,
    create_time     DATETIME(3)   NOT NULL DEFAULT NOW(3),
    PRIMARY KEY (id),
    KEY idx_work_order_event (work_order_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单流转留痕（只 INSERT，永不修改）';

-- ── 权限点与角色 ─────────────────────────────────────────────────────────────
-- 线上业务员与线下运维是**两个队列两种人**：线上看监控、初判、打电话、决定是否派人；
-- 线下到场、回填现场结果。分开授权才能回答“这一步该谁做”，也是工单流转的前提。
-- sys_permission 是**全局字典（无 tenant_id）**，列名是 code/name/type/sort（踩过一次：
-- 按直觉写成 permission_code/permission_name/resource_type 会让整个迁移链起不来）。
INSERT INTO sys_permission (id, parent_id, code, name, type, path, sort, status, create_time, update_time, deleted)
VALUES (20270001, 0, 'workorder:online:list',    '线上工单队列', 'MENU', '/workorder/online', 70, 1, NOW(3), NOW(3), 0),
       (20270002, 0, 'workorder:field:list',     '线下工单队列', 'MENU', '/workorder/field',  71, 1, NOW(3), NOW(3), 0),
       (20270010, 0, 'workorder:claim',          '认领工单',     'API',  NULL, 72, 1, NOW(3), NOW(3), 0),
       (20270011, 0, 'workorder:resolve',        '处理并反馈',   'API',  NULL, 73, 1, NOW(3), NOW(3), 0),
       (20270012, 0, 'workorder:dispatch-field', '派发线下工单', 'API',  NULL, 74, 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO sys_role (id, tenant_id, role_code, role_name, remark, status, create_time, update_time, deleted)
VALUES (20271001, 1, 'online_ops', '线上业务员', '异常初判、联系客户、决定是否派线下', 1, NOW(3), NOW(3), 0),
       (20271002, 1, 'field_ops',  '线下运维',   '到场处理与现场回填',                 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name);

INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id, create_time, update_time, deleted)
VALUES (20272001, 1, 20271001, 20270001, NOW(3), NOW(3), 0),
       (20272002, 1, 20271001, 20270010, NOW(3), NOW(3), 0),
       (20272003, 1, 20271001, 20270011, NOW(3), NOW(3), 0),
       (20272004, 1, 20271001, 20270012, NOW(3), NOW(3), 0),
       (20272005, 1, 20271002, 20270002, NOW(3), NOW(3), 0),
       (20272006, 1, 20271002, 20270010, NOW(3), NOW(3), 0),
       (20272007, 1, 20271002, 20270011, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
