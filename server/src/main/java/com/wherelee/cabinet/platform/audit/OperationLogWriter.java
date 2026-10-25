package com.wherelee.cabinet.platform.audit;

import com.wherelee.cabinet.domain.entity.SysOperationLog;
import com.wherelee.cabinet.infrastructure.mapper.SysOperationLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
