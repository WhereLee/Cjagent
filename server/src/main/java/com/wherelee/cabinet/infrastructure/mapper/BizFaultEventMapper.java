package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizFaultEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 故障事件写入（运营工单队列在第 14 刀，这里只负责记事实）。
 */
@Mapper
public interface BizFaultEventMapper extends BaseMapper<BizFaultEvent> {
}
