package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.SysOperationLog;

/**
 * 审计日志写入。sys_operation_log 在租户白名单内，因此写入不依赖租户上下文
 * （平台侧操作没有租户，也必须留得下痕）。
 */
public interface SysOperationLogMapper extends BaseMapper<SysOperationLog> {
}
