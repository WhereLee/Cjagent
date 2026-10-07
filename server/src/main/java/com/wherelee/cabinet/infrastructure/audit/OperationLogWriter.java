package com.wherelee.cabinet.infrastructure.audit;

import com.wherelee.cabinet.domain.entity.SysOperationLog;
import com.wherelee.cabinet.infrastructure.mapper.SysOperationLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计落库（技术写入器，与 {@link OperationLogAspect} 同属 infrastructure 层）。
 *
 * <p>{@code REQUIRES_NEW} 是这条链的关键：业务在同一事务里回滚时，"某人尝试过并且失败"
 * 这条事实必须保留。用默认的 REQUIRED，业务回滚会把审计记录一起带走——而审计最有价值的
 * 场景恰好是失败。
 *
 * <p>写失败只记 error、<b>不向上抛</b>：审计是旁路能力，不该因为日志写不进去就把用户
 * 正常请求打挂。代价要说清楚：<b>审计不是强一致的</b>，极端情况下会有"有请求无记录"，
 * 所以它不能当计费/对账依据，那些场景必须走业务表或消息（阶段 1 的业务事件走 RocketMQ）。
 */
@Service
public class OperationLogWriter {

    private static final Logger log = LoggerFactory.getLogger(OperationLogWriter.class);

    private final SysOperationLogMapper mapper;

    public OperationLogWriter(SysOperationLogMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(SysOperationLog record) {
        try {
            mapper.insert(record);
        } catch (RuntimeException e) {
            log.error("审计写入失败 module={} operation={} uri={}",
                    record.getModule(), record.getOperation(), record.getRequestUri(), e);
        }
    }
}
