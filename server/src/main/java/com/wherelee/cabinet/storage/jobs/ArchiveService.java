package com.wherelee.cabinet.storage.jobs;

import com.wherelee.cabinet.infrastructure.mapper.BizArchiveMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
@ConditionalOnProperty(name = "cabinet.archive.enabled", havingValue = "true")
public class ArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveService.class);

    private final BizArchiveMapper mapper;
    private final TransactionTemplate txTemplate;

    @Value("${cabinet.archive.retention-days:90}")
    private int retentionDays;

    @Value("${cabinet.archive.batch-size:500}")
    private int batchSize;

    @Value("${cabinet.archive.max-batches-per-run:20}")
    private int maxBatchesPerRun;

    public ArchiveService(BizArchiveMapper mapper, TransactionTemplate txTemplate) {
        this.mapper = mapper;
        this.txTemplate = txTemplate;
    }

    public record ArchiveResult(int reportsArchived, int tasksArchived) {
    }

    @Scheduled(cron = "${cabinet.archive.cron:0 20 4 * * ?}")
    public void scheduledRun() {
        ArchiveResult result = runOnce();
        log.info("归档完成 上报流水={} 延迟任务={} 保留期={} 天",
                result.reportsArchived(), result.tasksArchived(), retentionDays);
    }

    public ArchiveResult runOnce() {
        return new ArchiveResult(archiveReports(), archiveTasks());
    }

    private int archiveReports() {
        int moved = 0;
        for (int round = 0; round < maxBatchesPerRun; round++) {
            List<Long> ids = mapper.pickReportIds(retentionDays, batchSize);
            if (ids.isEmpty()) {
                break;
            }
            moved += txTemplate.execute(status -> {
                mapper.copyReports(ids);
                return mapper.deleteArchivedReports(ids, retentionDays);
            });
            if (ids.size() < batchSize) {
                break;
            }
        }
        return moved;
    }

    private int archiveTasks() {
        int moved = 0;
        for (int round = 0; round < maxBatchesPerRun; round++) {
            List<Long> ids = mapper.pickTaskIds(retentionDays, batchSize);
            if (ids.isEmpty()) {
                break;
            }
            moved += txTemplate.execute(status -> {
                mapper.copyTasks(ids);
                return mapper.deleteArchivedTasks(ids, retentionDays);
            });
            if (ids.size() < batchSize) {
                break;
            }
        }
        return moved;
    }
}
