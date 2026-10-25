package com.wherelee.cabinet.device.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.device.domain.BizFaultEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BizFaultEventMapper extends BaseMapper<BizFaultEvent> {
}
