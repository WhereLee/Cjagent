package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.DisputeState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 寄存单 Mapper。
 *
 * <p>争议相关的儿条更新都写成**带条件的单列更新**而不是 `updateById`，两个理由：
 * ① 争议字段与订单主态是两个关心的事，走 `updateById` 会连着 `version` 一起碰，
 * 使“外层的结束事务回滚”与“争议记录要留下”这两件事无法共存（见 ItemDisputeService 的 REQUIRES_NEW）；
 * ② “只记第一次”、“不能把时间往后推”这类语义只能写在 SQL 里才真的原子。
 */
@Mapper
public interface BizStorageOrderMapper extends BaseMapper<BizStorageOrder> {

    /**
     * 记下争议起始时刻：只有仍为 NULL 时才写。
     *
     * <p>这不是为了省一次更新，而是为了**让反复点“我没落东西”无法把计费终点往后推**：
     * 一旦可以刷成最新时刻，“误报免除”反而变成加费。
     */
    @Update("update biz_storage_order set dispute_state = #{state}, dispute_started_at = #{at}, "
            + "update_time = NOW(3) where id = #{id} and dispute_started_at is null and deleted = 0")
    int markDisputeStart(@Param("id") Long id, @Param("state") DisputeState state,
                         @Param("at") LocalDateTime at);

    /** 推进复审进度（状态 + 次数）。次数由调用方给，不在 SQL 里 +1：上限判定需要拿到“这是第几次”。 */
    @Update("update biz_storage_order set dispute_state = #{state}, ai_review_count = #{reviews}, "
            + "update_time = NOW(3) where id = #{id} and deleted = 0")
    int markReview(@Param("id") Long id, @Param("state") DisputeState state,
                   @Param("reviews") int reviews);

    /** 给原主单打“遗留物被他人发现”标记：只记第一次（同一单不必刷时间）。 */
    @Update("update biz_storage_order set leftover_reported_at = #{at}, update_time = NOW(3) "
            + "where id = #{id} and leftover_reported_at is null and deleted = 0")
    int markLeftoverReported(@Param("id") Long id, @Param("at") LocalDateTime at);
}
