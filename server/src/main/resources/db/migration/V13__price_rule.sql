-- ============================================================================
-- V13：计价策略发布与按点位灰度（第 14D 刀）
--
-- 为什么现在把它从 yml 搬进 DB：yml 改价要发版、只能全租户一刀切、也回答不了
-- "这个点位这一单当时按哪一版算的"。搬进 DB 后要拿到的三件事是：
-- 按点位先试（灰度）、发布即生效（可定时）、历史单不受影响（价格已在快照里）。
--
-- 一个关键约束：<b>快照仍是结算的唯一依据</b>。这张表只决定"下一张新单按什么价"，
-- 永远不参与已下单的结算——所以任何回滚都不会追溯改变进行中的单（这是第 12 刀
-- 定下的口径，本刀只是把"价"从配置文件挪到数据库，没打算改它的语义）。
--
-- 为什么一个范围只允许一条“已生效”：两条同时生效的语义是“看哪条先到”，没人能预测。
-- 允许情形的例外：带未来 effective_at 的新版可以与旧版并存（旧版仍在服务，到点自动切换），
-- 所以“生效中”的定义是【status=LIVE 且 effective_at 不晚于当前时刻】中的最大版本。
-- 旧版本标 SUPERSEDED 而不是删除，为了留下可回滚的链路。
-- ============================================================================

CREATE TABLE IF NOT EXISTS biz_price_rule
(
    id                 BIGINT       NOT NULL COMMENT '雪花 ID',
    tenant_id          BIGINT       NOT NULL COMMENT '运营商 ID',
    -- NULL = 该租户全局默认；非空 = 站点级（灰度入口）
    site_id            BIGINT       NULL COMMENT '点位 ID；NULL 表示全局策略',
    version            INT          NOT NULL COMMENT '同一生效范围内的版本号，从 1 递增',
    status             VARCHAR(16)  NOT NULL DEFAULT 'LIVE' COMMENT 'LIVE 生效中 / SUPERSEDED 已被替代',
    -- 计费入参：全部整型点/分，任何浮点都不许进资金链路（S-01）
    deposit_points     BIGINT       NOT NULL COMMENT '每单固定押金点数',
    free_minutes       INT          NOT NULL COMMENT '免费窗口（分钟）',
    daily_cap_hours    INT          NOT NULL COMMENT '单日封顶小时数；0=不按日封顶',
    cap_days           INT          NOT NULL COMMENT '总计费天数封顶；0=不限',
    tolerance_minutes  INT          NOT NULL COMMENT '开门后的容错期（分钟），期满起计',
    remote_close_hours INT          NOT NULL COMMENT '远程结束的加收小时数（按该格口单价折算）',
    unit_small         BIGINT       NOT NULL COMMENT '小格口每计费小时点数',
    unit_medium        BIGINT       NOT NULL COMMENT '中格口每计费小时点数',
    unit_large         BIGINT       NOT NULL COMMENT '大格口每计费小时点数',
    -- 允许未来生效：灰度时"今晚零点切换"不需要人守着发布
    effective_at       DATETIME(3)  NOT NULL COMMENT '生效时刻（服务端时间，S-07）',
    published_by       BIGINT       NOT NULL COMMENT '发布人（后台账号 ID）。动钱的字段必须可归人',
    reason             VARCHAR(200) NOT NULL COMMENT '发布事由（调价依据、灰度范围说明）',
    create_time        DATETIME(3)  NOT NULL COMMENT '创建时间',
    update_time        DATETIME(3)  NOT NULL COMMENT '更新时间',
    deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    -- 同一范围内的版本号唯一：并发双击发布不会造出两条同版本
    UNIQUE KEY uk_price_scope_version (tenant_id, site_id, version),
    -- 取"当前生效"就是一次索引点查：按站点回退全局，各取一条最新 LIVE
    KEY idx_price_scope_live (tenant_id, site_id, status, effective_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='计价策略（只决定新单的价，结算仍读订单快照）';

-- 注：uk 里 site_id 可为 NULL，MySQL 唯一索引允许多个 NULL——意味着"全局策略"的版本号
-- 唯一性靠应用层保证。这里明写而不偷偷加一个 site_id=0 的魔法值：那样"全局"就有两种表达了。

-- 计价策略的权限点：只有两个，没有 preview（预览是纯函数，跟着 list 权限走）；
-- 回滚不单独设权限点，因为它就是一次"把旧内容再发布一次"，需要的是同一个发布权限。
INSERT INTO sys_permission (id, parent_id, code, name, type, path, sort, status, create_time, update_time, deleted)
VALUES
    (20260020, 0, 'pricing:rule:list',    '计价策略查看', 'MENU', '/pricing/rules', 70, 1, NOW(3), NOW(3), 0),
    (20260021, 0, 'pricing:rule:publish', '发布计价策略', 'API',  NULL,             71, 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), path = VALUES(path), sort = VALUES(sort), update_time = NOW(3);

INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id, create_time, update_time, deleted)
SELECT 20262000 + p.id, g.tenant_id, g.role_id, p.id, NOW(3), NOW(3), 0
FROM (SELECT DISTINCT rp.tenant_id, rp.role_id
      FROM sys_role_permission rp
               JOIN sys_permission sp ON sp.id = rp.permission_id
      WHERE sp.code = 'system:user:list'
        AND rp.deleted = 0
        AND sp.deleted = 0) g,
     sys_permission p
WHERE p.id IN (20260020, 20260021)
  AND p.deleted = 0
ON DUPLICATE KEY UPDATE update_time = NOW(3);
