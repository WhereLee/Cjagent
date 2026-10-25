package com.wherelee.cabinet.servicedesk.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrderEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BizWorkOrderEventMapper extends BaseMapper<BizWorkOrderEvent> {
}
