package com.wherelee.cabinet.locker.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BizCabinetMapper extends BaseMapper<BizCabinet> {
}
