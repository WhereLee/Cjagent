-- ============================================================================
-- V11：物品争议状态与柜内证据留底（第 13C 刀，docs/门态与物品争议设计.md §5）
--
-- 为什么要单独一张证据表：争议阶梯的判断顺序是"红外 → 拍照 → AI 识图"，而这三步都是
-- **对某一时刻的一次观测**。只把结论写在格口的 presence 列上（那是"当前态"），事后就没法回答
-- "客服判定误报的依据是哪一张图、AI 当时看了什么时候的照片"——纠纷处理要的正是这两件事。
-- 所以格口存当前结论，这张表存每一次观测，两边职责不重叠。
--
-- photo_ref 只存引用不存图片：本项目不实现拍照与图片存储（设备边界见设计文档 §9），
-- 真接设备时这里放对象存储的 key。留一个可空引用而不是现在就搭对象存储，
-- 是为了让"判定输入是什么"这件事在数据模型里先成立。
-- ============================================================================

ALTER TABLE biz_storage_order
    -- 争议状态只在"结束被拦下"之后才出现，所以可空；NULL = 没有争议
    ADD COLUMN dispute_state VARCHAR(24) NULL COMMENT '物品争议状态：ITEM_DISPUTED（用户否认）/ AI_REVIEWED（已复审）/ HUMAN_REVIEW（转人工）'
        AFTER close_reason,
    -- 争议起始时刻：判为设备误报时，结算终点回退到这里（争议期间本就在计费，免除的是这一段）
    ADD COLUMN dispute_started_at DATETIME(3) NULL COMMENT '第一次因柜内物品被拒绝结束的时刻（误报免除的计费终点）'
        AFTER dispute_state,
    ADD COLUMN ai_review_count INT NOT NULL DEFAULT 0 COMMENT '本单已触发的 AI 复审次数（防反复点否认刷模型调用）'
        AFTER dispute_started_at,
    -- 上一位用户的东西被后来者发现时打的时间戳：让他自己的页面能显示"你的物品曾被他人看到"
    ADD COLUMN leftover_reported_at DATETIME(3) NULL COMMENT '后续使用者上报本格口遗留他人物品的时刻（原主可见，纠纷还原）'
        AFTER ai_review_count;

ALTER TABLE biz_storage_order
    ADD CONSTRAINT ck_order_dispute_state CHECK (
        dispute_state IS NULL OR dispute_state IN ('ITEM_DISPUTED', 'AI_REVIEWED', 'HUMAN_REVIEW')
    );

-- 一次观测一条记录，只增不改（与点数流水同一取向：改过的证据不能作为纠纷依据）
CREATE TABLE IF NOT EXISTS biz_item_evidence
(
    id              BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id       BIGINT      NOT NULL COMMENT '运营商 ID',
    order_id        BIGINT      NULL COMMENT '产生这条证据的订单（上报遗留物时可能已无活动单）',
    cabinet_id      BIGINT      NOT NULL COMMENT '柜机 ID',
    compartment_id  BIGINT      NOT NULL COMMENT '格口 ID',
    -- 证据来源：红外 / AI 看图复审 / 设备探测（现场结单时的物检）
    source          VARCHAR(16) NOT NULL COMMENT 'INFRARED / AI / DEVICE_PROBE',
    presence        VARCHAR(16) NOT NULL COMMENT '结论：PRESENT / ABSENT / UNKNOWN（三值，不许折成布尔）',
    -- AI 的"无法判断"必须能落库：把它强行归到有或无，就是在最该谨慎的地方造假
    confidence      DECIMAL(4, 3) NULL COMMENT 'AI 置信度（0~1），仅 source=AI 有值',
    photo_ref       VARCHAR(255) NULL COMMENT '照片引用（对象存储 key）；本项目不落地图片，真接设备时补',
    note            VARCHAR(255) NULL COMMENT '人看的说明：误报判定依据、复审结论等',
    create_time     DATETIME(3) NOT NULL COMMENT '创建时间',
    update_time     DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    -- 争议与纠纷还原都是"拿这一个格口的观测时间线"，所以按格口 + 时间取数
    KEY idx_evidence_compartment (compartment_id, create_time),
    -- 红外误报率这类指标按订单聚合（AI 说无 vs 红外说有）
    KEY idx_evidence_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='柜内物检证据留底（一次观测一条，只增不改）';
