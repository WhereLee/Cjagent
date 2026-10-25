package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.wherelee.cabinet.domain.entity.SysOperationLog;

@Mapper
public interface SysOperationLogMapper extends BaseMapper<SysOperationLog> {
}
