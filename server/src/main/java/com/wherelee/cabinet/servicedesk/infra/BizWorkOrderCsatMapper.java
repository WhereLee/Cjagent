package com.wherelee.cabinet.servicedesk.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrderCsat;
import org.apache.ibatis.annotations.Mapper;

/** 工单满意度读写（一单一次，唯一索引兜底）。 */
@Mapper
public interface BizWorkOrderCsatMapper extends BaseMapper<BizWorkOrderCsat> {
}
