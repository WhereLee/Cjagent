-- ============================================================================
-- V4: 储物柜业务地基（点位 / 柜机型号模板 / 柜机 / 格口 / 寄存单 / 设备指令与上报 /
--      故障事件 / 延迟任务 / 点数账户与流水 / 押金 / 订单归档表）
--
-- 本文件只建**结构与约束**，不含任何业务逻辑（资金逻辑在第 12 刀、争抢在第 9~10 刀）。
-- 之所以现在就把点数账户与押金表建出来：否则第 12 刀要回头改寄存单表字段（补数据）。
--
-- 隔离口径（与 CabinetTenantHandler 白名单成对，见 docs/架构约定.md §3.2）：
--   · 带 tenant_id、受租户拦截器自动过滤：本文件除 biz_cabinet_model 外全部表
--   · 全局共享、进白名单：biz_cabinet_model（硬件型号是平台字典，租户只引用，S-14）
--
-- 三条设计决定，都不是"顺手为之"：
--   1) 防超卖交给数据库，不交给应用自觉：`uk_order_active_slot (slot_id, active_flag)`。
--      active_flag 在活动期写 1、订单结束置 NULL；MySQL 唯一索引**允许多个 NULL**，
--      于是"同一格口两条活动单"在存储层就插不进去，历史单却可以无限多条。
--   2) 主键不含日期、不做 MySQL 分区（S-12）：冷热分层用 created_at 冗余列 + 归档表。
--      分区要求分区键进入每个唯一键，会与雪花主键冲突，收益又主要在专职 DBA 的运维侧。
--   3) 计费与状态推进只认服务端时间（S-07）：设备上报自带 reported_at 仅作参考与对账，
--      权威时间是入库时刻 received_at。柜机时钟错乱不该变成大面积错收。
--
-- 状态用 VARCHAR 而不是 TINYINT 位图：状态机是本项目的核心资产，
-- `status='ACTIVE'` 在 EXPLAIN、日志与工单里都能直接读，枚举值映射在 Java 侧收口。
-- ============================================================================

-- ---------- 点位 ----------
CREATE TABLE IF NOT EXISTS biz_site
(
    id          BIGINT         NOT NULL COMMENT '雪花 ID',
    tenant_id   BIGINT         NOT NULL COMMENT '运营商（租户）ID',
    site_code   VARCHAR(32)    NOT NULL COMMENT '点位编码，租户内唯一，扫码深链用它',
    name        VARCHAR(64)    NOT NULL COMMENT '点位名称，如"万象城 B1 东区"',
    category    VARCHAR(16)    NOT NULL DEFAULT 'MALL' COMMENT 'MALL / STATION / SCENIC / OTHER',
    address     VARCHAR(255)   NULL COMMENT '详细地址',
    longitude   DECIMAL(10, 6) NULL COMMENT '经度，仅用于"就近推荐"，不做地理围栏',
    latitude    DECIMAL(10, 6) NULL COMMENT '纬度',
    status      TINYINT        NOT NULL DEFAULT 1 COMMENT '1 营业 0 停用',
    create_time DATETIME(3)    NOT NULL COMMENT '创建时间',
    update_time DATETIME(3)    NOT NULL COMMENT '更新时间',
    deleted     TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_site_tenant_code (tenant_id, site_code),
    KEY idx_site_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='寄存点位';

-- ---------- 柜机型号模板（平台字典，无 tenant_id）----------
CREATE TABLE IF NOT EXISTS biz_cabinet_model
(
    id           BIGINT      NOT NULL COMMENT '雪花 ID',
    model_code   VARCHAR(32) NOT NULL COMMENT '型号编码，全局唯一',
    model_name   VARCHAR(64) NOT NULL COMMENT '型号名，如"24 门标准柜"',
    large_count  INT         NOT NULL DEFAULT 0 COMMENT '大格口数量',
    medium_count INT         NOT NULL DEFAULT 0 COMMENT '中格口数量',
    small_count  INT         NOT NULL DEFAULT 0 COMMENT '小格口数量',
    remark       VARCHAR(255) NULL COMMENT '备注',
    status       TINYINT     NOT NULL DEFAULT 1 COMMENT '1 可用 0 下架',
    create_time  DATETIME(3) NOT NULL COMMENT '创建时间',
    update_time  DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cabinet_model_code (model_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='柜机型号模板：建柜机时按它批量生成格口，禁止人工逐门录入（防呆）';

-- ---------- 柜机 ----------
CREATE TABLE IF NOT EXISTS biz_cabinet
(
    id              BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id       BIGINT      NOT NULL COMMENT '运营商 ID',
    site_id         BIGINT      NOT NULL COMMENT '所属点位',
    cabinet_no      VARCHAR(32) NOT NULL COMMENT '柜机编号，**全局唯一**：二维码里带的就是它',
    name            VARCHAR(64) NOT NULL COMMENT '柜机名，如"A 区 1 号柜"',
    model_id        BIGINT      NOT NULL COMMENT '型号模板 ID',
    cabinet_status  VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED / DISABLED / FAULT',
    -- 在线状态以 Redis 的 TTL 键为权威（心跳键过期即离线），这里只存最后心跳时间与上次判定结果，
    -- 用于重启后回填展示与"离线期间发生了什么"的对账，不参与实时判定
    online_state    VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'ONLINE / OFFLINE / UNKNOWN',
    last_heartbeat_at DATETIME(3) NULL COMMENT '最后心跳（服务端入库时间）',
    create_time     DATETIME(3) NOT NULL COMMENT '创建时间',
    update_time     DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cabinet_no (cabinet_no),
    KEY idx_cabinet_site (site_id, cabinet_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='柜机';

-- ---------- 格口 ----------
CREATE TABLE IF NOT EXISTS biz_compartment
(
    id          BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id   BIGINT      NOT NULL COMMENT '运营商 ID（冗余自柜机，隔离用）',
    cabinet_id  BIGINT      NOT NULL COMMENT '所属柜机',
    slot_no     VARCHAR(8)  NOT NULL COMMENT '格口序号，柜机内唯一，如 A01',
    size_type   VARCHAR(8)  NOT NULL COMMENT 'LARGE / MEDIUM / SMALL',
    status      VARCHAR(16) NOT NULL DEFAULT 'FREE' COMMENT 'FREE / RESERVED / OCCUPIED / FAULT / MAINTENANCE',
    -- 活动寄存单指针：占用中必须非空、空闲必须为空（不变量 2 的结构化表达）
    current_order_id BIGINT NULL COMMENT '当前占用该格口的寄存单，空闲为 NULL',
    version     INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(3) NOT NULL COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_slot_cabinet_no (cabinet_id, slot_no),
    -- 分配查询的唯一形态是"某柜机 + 某尺寸 + 空闲"：cabinet_id 先把范围缩到 24 行，
    -- 所以它必须在最左；status 单独建索引毫无意义（区分度只有 5 个值，优化器不会用）
    KEY idx_slot_acquire (cabinet_id, size_type, status),
    KEY idx_slot_current_order (current_order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='格口（仓位）';

-- ---------- 寄存单 ----------
CREATE TABLE IF NOT EXISTS biz_storage_order
(
    id            BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id     BIGINT      NOT NULL COMMENT '运营商 ID',
    order_no      VARCHAR(32) NOT NULL COMMENT '业务单号，对外可见、幂等用',
    site_id       BIGINT      NOT NULL COMMENT '点位（冗余，避免列表页 join）',
    cabinet_id    BIGINT      NOT NULL COMMENT '柜机',
    slot_id       BIGINT      NOT NULL COMMENT '格口',
    size_type     VARCHAR(8)  NOT NULL COMMENT '尺寸快照（改配置不影响历史单）',
    customer_id   BIGINT      NOT NULL COMMENT '寄存客户',
    status        VARCHAR(16) NOT NULL DEFAULT 'INIT' COMMENT 'INIT / RESERVED / OPENING / STORED / ACTIVE / TEMP_OPEN / SETTLING / CLOSED / CANCELLED / ABNORMAL / EXPIRED',
    -- 防超卖的唯一索引靠这一列：活动期写 1，终态置 NULL（NULL 可重复，故历史单不受限）
    active_flag   TINYINT     NULL COMMENT '1=活动中的单；终态置 NULL',
    voucher_code  VARCHAR(16) NOT NULL COMMENT '取件码：随机不可枚举（不用自增短码，防越权猜码）',
    -- 价格策略快照：JSON 列存当次计费规则与计算明细，改价不污染历史、投诉可重算（S-06）。
    -- 本刀只以字符串读写，第 12 刀再接 MP JacksonTypeHandler 做对象化
    pricing_snapshot JSON     NULL COMMENT '计费策略快照与明细',
    estimate_minutes INT      NOT NULL DEFAULT 0 COMMENT '预估时长（分钟），决定预扣点数',
    started_at    DATETIME(3) NULL COMMENT '开始计费时间（服务端时间）',
    expected_finish_at DATETIME(3) NULL COMMENT '预计结束时间，超时扫描用',
    finished_at   DATETIME(3) NULL COMMENT '实际结束时间',
    temp_open_count INT        NOT NULL DEFAULT 0 COMMENT '临时开柜次数（免费 N 次，超出计费）',
    deposit_points   BIGINT   NOT NULL DEFAULT 0 COMMENT '本单押金黄（点数），退还不进入点数消费流水',
    frozen_points    BIGINT   NOT NULL DEFAULT 0 COMMENT '已冻结待核销的点数',
    settled_points   BIGINT   NOT NULL DEFAULT 0 COMMENT '已核销（真实收到）的点数',
    arrears_points   BIGINT   NOT NULL DEFAULT 0 COMMENT '结算补扣失败的欠费点数：欠的是债，不阻断取件',
    last_error    VARCHAR(255) NULL COMMENT '最近一次异常原因（含设备谎报、超时未关等）',
    version       INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本，状态迁移必须带版本',
    create_time   DATETIME(3) NOT NULL COMMENT '创建时间（=冷热分层的切分列，S-12）',
    update_time   DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    -- ★ 结构性防超卖：同一格口同时只能存在一条活动单
    UNIQUE KEY uk_order_active_slot (slot_id, active_flag),
    UNIQUE KEY uk_order_voucher (voucher_code),
    KEY idx_order_customer (customer_id, status, create_time),
    -- 超时/结算扫描：满足最左前缀，否则优化器直接不用它
    KEY idx_order_scan (status, expected_finish_at),
    KEY idx_order_tenant_time (tenant_id, create_time),
    KEY idx_order_cabinet (cabinet_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='寄存单（一次存→取的全生命周期）';

-- ---------- 设备指令流水 ----------
CREATE TABLE IF NOT EXISTS biz_device_command
(
    id           BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id    BIGINT      NOT NULL COMMENT '运营商 ID',
    order_id     BIGINT      NULL COMMENT '关联寄存单，运维开柜等可为空',
    cabinet_id   BIGINT      NOT NULL COMMENT '目标柜机',
    slot_id      BIGINT      NULL COMMENT '目标格口',
    request_id   VARCHAR(64) NOT NULL COMMENT '**幂等键**：设备回执按它关联，重试不改此值',
    action       VARCHAR(24) NOT NULL COMMENT 'OPEN / OPEN_TEMP / CLOSE_VERIFY / FORCE_OPEN',
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENT / ACKED / SUCCEEDED / FAILED / TIMEOUT',
    retry_count  INT         NOT NULL DEFAULT 0 COMMENT '重试次数（超时无回执时递增，达阈值判 TIMEOUT）',
    operator_id  BIGINT      NULL COMMENT '人工发起时记录操作人（强制开柜必须留痕）',
    sent_at      DATETIME(3) NULL COMMENT '下发时间（服务端）',
    acked_at     DATETIME(3) NULL COMMENT '回执时间（服务端）',
    last_error   VARCHAR(255) NULL COMMENT '最近失败原因',
    create_time  DATETIME(3) NOT NULL COMMENT '创建时间',
    update_time  DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cmd_request_id (request_id),
    KEY idx_cmd_pending (status, sent_at),
    KEY idx_cmd_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='设备指令流水（可重放、可对账、可证明"我没猜成功"）';

-- ---------- 设备上报流水（只 INSERT，永不 UPDATE）----------
CREATE TABLE IF NOT EXISTS biz_device_report
(
    id          BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id   BIGINT      NOT NULL COMMENT '运营商 ID',
    cabinet_id  BIGINT      NOT NULL COMMENT '柜机',
    slot_id     BIGINT      NULL COMMENT '格口',
    order_id    BIGINT      NULL COMMENT '关联寄存单（能对上时填）',
    seq         BIGINT      NOT NULL COMMENT '设备侧单调序号，**乱序收敛的依据**',
    event_type  VARCHAR(24) NOT NULL COMMENT 'DOOR_OPENED / DOOR_CLOSED / HEARTBEAT / FAULT / STATE_SNAPSHOT',
    dedup_key   VARCHAR(96) NOT NULL COMMENT 'cabinetId:seq 组合键，唯一索引挡住重复上报',
    payload     JSON        NULL COMMENT '事件内容',
    reported_at DATETIME(3) NOT NULL COMMENT '设备声称的时间，**仅用于对账**，不参与计费',
    received_at DATETIME(3) NOT NULL COMMENT '服务端入库时间，权威时间（S-07）',
    create_time DATETIME(3) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_dedup (dedup_key),
    KEY idx_report_slot (cabinet_id, slot_id, seq),
    KEY idx_report_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='设备上报流水：只增不改，是"状态不一致"与"柜机重连补报"的原始证据';

-- ---------- 格口/柜机故障事件 ----------
CREATE TABLE IF NOT EXISTS biz_fault_event
(
    id          BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id   BIGINT      NOT NULL COMMENT '运营商 ID',
    cabinet_id  BIGINT      NOT NULL COMMENT '柜机',
    slot_id     BIGINT      NULL COMMENT '格口，柜机级故障为空',
    fault_type  VARCHAR(24) NOT NULL COMMENT 'OPEN_FAILED / DOOR_NOT_CLOSED / NO_REPORT / OFFLINE / SELF_CHECK',
    action      VARCHAR(24) NOT NULL COMMENT 'MARK_UNAVAILABLE / MARK_ONLINE / ALARM / RECOVER',
    fail_count  INT         NOT NULL DEFAULT 0 COMMENT '当时累计失败次数（阈值触发停用）',
    operator_id BIGINT      NULL COMMENT '人工恢复/停用时记录操作人',
    reason      VARCHAR(255) NULL COMMENT '原因描述',
    create_time DATETIME(3) NOT NULL COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_fault_cabinet (cabinet_id, create_time),
    KEY idx_fault_slot (slot_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '"设备损坏 → 业务自保"的全过程留痕';

-- ---------- 统一延迟任务表（超时未关 / 超时未取 / 押金退款重试 / 对账批次共用）----------
CREATE TABLE IF NOT EXISTS biz_delay_task
(
    id              BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id       BIGINT      NOT NULL COMMENT '运营商 ID',
    task_type       VARCHAR(32) NOT NULL COMMENT 'SLOT_RELEASE / OVERDUE_CHECK / DEPOSIT_REFUND / RECONCILE',
    biz_key         VARCHAR(64) NOT NULL COMMENT '业务键（多为订单号），与类型组成唯一键 → 同类任务不重复登记',
    fire_at         DATETIME(3) NOT NULL COMMENT '应执行时间',
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / DONE / FAILED / DEAD',
    attempt         INT         NOT NULL DEFAULT 0 COMMENT '已尝试次数（指数退避依据）',
    last_error      VARCHAR(255) NULL COMMENT '最近失败原因',
    -- 多实例抢占：租约 + `FOR UPDATE SKIP LOCKED` 扫描，一个实例取走的行对其他实例不可见
    lease_owner     VARCHAR(64) NULL COMMENT '持有者标识（实例 ID + 随机串）',
    lease_expire_at DATETIME(3) NULL COMMENT '租约到期时间；过期可被他人接管（进程被 kill 不永久卡死）',
    create_time     DATETIME(3) NOT NULL COMMENT '创建时间',
    update_time     DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_type_key (task_type, biz_key),
    KEY idx_task_due (status, fire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='延迟/补偿任务表（后端可插拔：扫表抢占 / MQ 定时 / Redis ZSet）';

-- ---------- 点数账户 ----------
CREATE TABLE IF NOT EXISTS biz_point_account
(
    id            BIGINT  NOT NULL COMMENT '雪花 ID',
    tenant_id     BIGINT  NOT NULL COMMENT '运营商 ID',
    customer_id   BIGINT  NOT NULL COMMENT '客户 ID',
    points        BIGINT  NOT NULL DEFAULT 0 COMMENT '可用点数（单位=分，整型）',
    frozen_points BIGINT  NOT NULL DEFAULT 0 COMMENT '冻结中的点数（占位而非消耗）',
    version       INT     NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time   DATETIME(3) NOT NULL COMMENT '创建时间',
    update_time   DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted       TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_point_account_customer (customer_id),
    -- CHECK 是最后一道兜底：第一道是条件 UPDATE `WHERE points >= ?` 靠影响行数判定。
    -- 两道都要有：只靠 CHECK 会让并发变成异常风暴，只靠 UPDATE 则挡不住代码里的手误
    CONSTRAINT ck_point_account_nonneg CHECK (points >= 0 AND frozen_points >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='点数账户（余额是派生值，可由流水重算校验）';

-- ---------- 点数流水（只 INSERT，不可改）----------
CREATE TABLE IF NOT EXISTS biz_point_txn
(
    id            BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id     BIGINT      NOT NULL COMMENT '运营商 ID',
    customer_id   BIGINT      NOT NULL COMMENT '客户 ID',
    biz_type      VARCHAR(24) NOT NULL COMMENT 'RECHARGE / FREEZE / SETTLE / UNFREEZE / REFUND / ADJUST',
    amount        BIGINT      NOT NULL COMMENT '变动点数，正=入账，负=出账',
    balance_after BIGINT      NULL COMMENT '变动后余额快照，便于人读与对账（真相仍是 Σamount）',
    ref_type      VARCHAR(24) NULL COMMENT '关联单据类型，如 STORAGE_ORDER / DEPOSIT',
    ref_id        BIGINT      NULL COMMENT '关联单据 ID',
    biz_no        VARCHAR(64) NOT NULL COMMENT '幂等业务号（如通道流水号、订单号+动作）',
    remark        VARCHAR(255) NULL COMMENT '备注',
    create_time   DATETIME(3) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    -- 幂等入账下沉到索引层：同一业务事件（充值回调重复送达等）只能入账一次
    UNIQUE KEY uk_txn_biz (biz_type, biz_no),
    KEY idx_txn_customer (customer_id, create_time),
    KEY idx_txn_ref (ref_type, ref_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='点数流水：只增不改，余额必须能被它重算出来';

-- ---------- 押金（独立义务：必须可退，不与点数消费混记）----------
CREATE TABLE IF NOT EXISTS biz_deposit
(
    id          BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id   BIGINT      NOT NULL COMMENT '运营商 ID',
    order_id    BIGINT      NOT NULL COMMENT '寄存单',
    customer_id BIGINT      NOT NULL COMMENT '客户',
    points      BIGINT      NOT NULL COMMENT '押金点数（S-03 每单固定）',
    status      VARCHAR(16) NOT NULL DEFAULT 'HELD' COMMENT 'HELD / REFUNDING / REFUNDED / FAILED',
    held_txn_id BIGINT      NULL COMMENT '收取时的流水 ID',
    refund_txn_id BIGINT    NULL COMMENT '退还时的流水 ID',
    held_at     DATETIME(3) NOT NULL COMMENT '收取时间',
    refunded_at DATETIME(3) NULL COMMENT '退还成功时间',
    fail_reason VARCHAR(255) NULL COMMENT '退款失败原因（未退清单靠它定位）',
    create_time DATETIME(3) NOT NULL COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_deposit_order (order_id),
    -- "订单已关闭但押金未退"的悬挂扫描走这条索引（不变量 4）
    KEY idx_deposit_status_time (status, held_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='押金记录：与点数消费义务性质不同，故独立成表';

-- ---------- 寄存单归档表（S-12：冷热分层不用分区）----------
-- 与 biz_storage_order 同构（去掉 active_flag 唯一约束：归档里没有活动单）。
-- 归档作业按 `status IN 终态 AND create_time < 阈值` 分批 INSERT...SELECT + DELETE，
-- 完整性断言：归档后 热表行数 + 冷表行数 == 归档前行数。
CREATE TABLE IF NOT EXISTS biz_storage_order_archive
(
    id               BIGINT      NOT NULL COMMENT '雪花 ID（与热表同值）',
    tenant_id        BIGINT      NOT NULL COMMENT '运营商 ID',
    order_no         VARCHAR(32) NOT NULL COMMENT '业务单号',
    site_id          BIGINT      NOT NULL COMMENT '点位',
    cabinet_id       BIGINT      NOT NULL COMMENT '柜机',
    slot_id          BIGINT      NOT NULL COMMENT '格口',
    size_type        VARCHAR(8)  NOT NULL COMMENT '尺寸快照',
    customer_id      BIGINT      NOT NULL COMMENT '客户',
    status           VARCHAR(16) NOT NULL COMMENT '终态快照',
    voucher_code     VARCHAR(16) NOT NULL COMMENT '取件码',
    pricing_snapshot JSON        NULL COMMENT '计费策略快照',
    estimate_minutes INT         NOT NULL DEFAULT 0 COMMENT '预估时长',
    started_at       DATETIME(3) NULL COMMENT '开始计费时间',
    expected_finish_at DATETIME(3) NULL COMMENT '预计结束时间',
    finished_at      DATETIME(3) NULL COMMENT '实际结束时间',
    temp_open_count  INT         NOT NULL DEFAULT 0 COMMENT '临时开柜次数',
    deposit_points   BIGINT      NOT NULL DEFAULT 0 COMMENT '押金黄',
    frozen_points    BIGINT      NOT NULL DEFAULT 0 COMMENT '冻结点数',
    settled_points   BIGINT      NOT NULL DEFAULT 0 COMMENT '核销点数',
    arrears_points   BIGINT      NOT NULL DEFAULT 0 COMMENT '欠费点数',
    last_error       VARCHAR(255) NULL COMMENT '最近异常原因',
    archived_at      DATETIME(3) NOT NULL COMMENT '归档时间',
    create_time      DATETIME(3) NOT NULL COMMENT '原创建时间（冷热切分列）',
    update_time      DATETIME(3) NOT NULL COMMENT '原更新时间',
    deleted          TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_archive_order_no (order_no),
    KEY idx_archive_customer (customer_id, create_time),
    KEY idx_archive_tenant_time (tenant_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='寄存单归档表（历史只读，冷查询入口显式走这张表）';
