package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizWorkOrderEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工单留痕流水：只提供插入与查询，<b>不提供任何 update/delete</b>。
 *
 * <p>之所以单独一个 Mapper 而不是塑进工单 Mapper：这张表的“只能写一次”是审计属性，
 * 混在一起很容易被后人顺手加上一条 update 而把留痕变成可改写历史。
 */
@Mapper
public interface BizWorkOrderEventMapper extends BaseMapper<BizWorkOrderEvent> {
}
