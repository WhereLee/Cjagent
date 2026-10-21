package com.wherelee.cabinet.notify.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.notify.api.NoticePort.View;
import com.wherelee.cabinet.notify.domain.BizNotice;
import com.wherelee.cabinet.notify.infra.BizNoticeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 通知与人工待办（19D 第 2 条）。
 *
 * <p>系统里没有短信、外呼、推送通道，所以这里**不假装发出去过**：
 * <ul>
 *   <li>{@code PHONE_CALL} = 一条挂在工单上的待办"必须电话联系客户"，业务员打完回填结论；
 *       没回填就一直欠着，可追责；</li>
 *   <li>{@code SYSTEM_MESSAGE} = 站内消息，客户自己打开小程序才看得到。</li>
 * </ul>
 * 这两种形态共用一张表：它们的共同点是"某条异常，该被某人看到一次"，区别只在收件人是谁。
 */
@Service
public class NoticeService implements NoticePort {

    private static final Logger log = LoggerFactory.getLogger(NoticeService.class);

    private final BizNoticeMapper notices;

    public NoticeService(BizNoticeMapper notices) {
        this.notices = notices;
    }

    /**
     * 幂等发出。<b>去重靠唯一索引</b>（{@code tenant + dedup_key}），不是"先查一条有没有"：
     * 提醒阶梯每轮巡检都会重进这里，先查再插在并发续排下会堆出一串一模一样的待办。
     *
     * @return true 表示这是第一次发出
     */
    @Transactional
    public boolean emit(Long tenantId, com.wherelee.cabinet.notify.api.NoticeChannel channel, String kind,
                        String dedupKey, String content,
                        Long customerId, Long workOrderId, String orderNo) {
        BizNotice notice = new BizNotice();
        notice.setTenantId(tenantId);
        notice.setChannel(channel == null ? null : channel.name());
        notice.setKind(kind);
        notice.setDedupKey(dedupKey);
        notice.setContent(content == null || content.isBlank() ? kind : truncate(content));
        notice.setCustomerId(customerId);
        notice.setWorkOrderId(workOrderId);
        notice.setOrderNo(orderNo);
        try {
            notices.insert(notice);
        } catch (DuplicateKeyException e) {
            // 同一档已经发过：正常分支，不是错误（阶梯会反复经过这里）
            return false;
        }
        log.info("通知已发 channel={} kind={} dedup={} customer={} workOrder={}",
                channel, kind, dedupKey, customerId, workOrderId);
        return true;
    }

    /**
     * 改写一条已发消息的内容（第 25 刀）：重置取件码后，凭证消息必须反映当前码。
     *
     * <p>改写而不是再发一条：{@code dedup_key} 的幂等会挡掉重发，而绕过幂等堆出第二跳
     * 旧码消息更糟——用户照着废码去柜机屏输，换来一次锁定。消息不存在（历史单没有凭证）
     * 就是 0 行，不必报错：这不是错误分支。
     */
    @Transactional
    public void rewrite(String dedupKey, String content) {
        notices.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<BizNotice>lambdaUpdate()
                .eq(BizNotice::getDedupKey, dedupKey)
                .set(BizNotice::getContent, truncate(content == null || content.isBlank() ? dedupKey : content)));
    }

    /** 客户可见的消息（含已读的最近若干条：他只关心"有没有我没看到的异常"）。 */
    public List<View> forCustomer(Long customerId) {
        return notices.selectList(Wrappers.<BizNotice>lambdaQuery()
                .eq(BizNotice::getCustomerId, customerId)
                .eq(BizNotice::getChannel, com.wherelee.cabinet.notify.api.NoticeChannel.SYSTEM_MESSAGE.name())
                .orderByAsc(BizNotice::getDoneAt)
                .orderByDesc(BizNotice::getCreateTime)
                .last("limit 50")).stream().map(this::toView).toList();
    }

    /** 业务员侧"还没打给客户"的任务清单。 */
    public List<View> pendingCalls() {
        return notices.selectList(Wrappers.<BizNotice>lambdaQuery()
                .eq(BizNotice::getChannel, com.wherelee.cabinet.notify.api.NoticeChannel.PHONE_CALL.name())
                .isNull(BizNotice::getDoneAt)
                .orderByAsc(BizNotice::getCreateTime)
                .last("limit 200")).stream().map(this::toView).toList();
    }

    /** 客户标记已读：只能读到自己那条（条件里带 customer_id）。 */
    @Transactional
    public void markRead(Long id, Long customerId) {
        if (notices.markReadForCustomer(id, customerId) == 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "这条消息不属于当前账号，或已读过");
        }
    }

    /** 回填电话结果。<b>结论必填</b>：只打个勾不留"联系上了没有"，这条待办就白设了。 */
    @Transactional
    public void markCallDone(Long id, String note) {
        if (note == null || note.isBlank()) {
            throw new BizException(ResultCode.BIZ_ERROR, "必须填写联系结果（联系上了/未接/约定多久处理）");
        }
        if (notices.markDone(id, truncate(note)) == 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "这通电话已回填过，或记录不存在");
        }
    }

    private String truncate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 297) + "...";
    }

    private View toView(BizNotice n) {
        return new View(n.getId(), n.getChannel(), n.getKind(), n.getContent(), n.getOrderNo(),
                n.getWorkOrderId(), n.getCreateTime(), n.getDoneAt(), n.getDoneNote());
    }
}
