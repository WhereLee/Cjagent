package com.wherelee.cabinet.application.task;

import com.wherelee.cabinet.infrastructure.mapper.BizArchiveMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 只增表的归档搬运（A-5）。
 *
 * <p>为什么默认关闭（{@code cabinet.archive.enabled=false}）：机制要先在库里躺着、
 * 用例要先跑通，但它一旦开着就会在每天低峰真的删行。等运维那边确认过备份节奏
 * （删掉的东西必须能靠备份找回来）再打开，而不是顺手写成 true 就上线。
 *
 * <p>搬多少、什么时候搬都写成配置，理由各不相同：
 * <ul>
 *   <li><b>保留期 90 天</b>（用户 2026-10-12 授权我定）：在线侧要能直接查到“上一个季度的账单与纠纷”，
 *       这是运营和客服的实际需要，不是容量算出来的数；</li>
 *   <li><b>归档表永久保留、只搬不删</b>（同一决定）：这套设计处处讲“留底”，也没有法规要求删除，
 *       真到需要清理时再单独立规则，不在这里默认删；</li>
 *   <li>单批 500、每轮最多 20 批：批大了长事务锁表，批小了首次追平要跑很久，
 *       有上限才不会在极端积压下把归档跑成无限循环。</li>
 * </ul>
 *
 * <p>每批一个事务：复制与删除要么一起成，要么都不做。<b>绝不在事务外先删后插</b>，
 * 那等于把“删掉但没留下”变成可能。
 */
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

    /** 一轮搬了多少行（跑完只报告，不悄悄跑）。 */
    public record ArchiveResult(int reportsArchived, int tasksArchived) {
    }

    /** 每天 04:20（低峰；分钟位故意不为 0，避开与整点任务的排队）。 */
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
                // 按“已进归档表”的条件删：复制被忽略（重复执行）的行也会在这里被清掉，
                // 而任何没复制成功的行都不会被删——幂等与不丢数据同时成立
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
