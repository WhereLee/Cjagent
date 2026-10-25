package com.wherelee.cabinet.notify.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.notify.api.NoticePort.NoticeView;
import com.wherelee.cabinet.notify.domain.BizNotice;
import com.wherelee.cabinet.notify.infra.BizNoticeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NoticeService implements NoticePort {

    private static final Logger log = LoggerFactory.getLogger(NoticeService.class);

    private final BizNoticeMapper notices;

    public NoticeService(BizNoticeMapper notices) {
        this.notices = notices;
    }

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
            return false;
        }
        log.info("通知已发 channel={} kind={} dedup={} customer={} workOrder={}",
                channel, kind, dedupKey, customerId, workOrderId);
        return true;
    }

    @Transactional
    public void rewrite(String dedupKey, String content) {
        notices.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<BizNotice>lambdaUpdate()
                .eq(BizNotice::getDedupKey, dedupKey)
                .set(BizNotice::getContent, truncate(content == null || content.isBlank() ? dedupKey : content)));
    }

    public List<NoticeView> forCustomer(Long customerId) {
        return notices.selectList(Wrappers.<BizNotice>lambdaQuery()
                .eq(BizNotice::getCustomerId, customerId)
                .eq(BizNotice::getChannel, com.wherelee.cabinet.notify.api.NoticeChannel.SYSTEM_MESSAGE.name())
                .orderByAsc(BizNotice::getDoneAt)
                .orderByDesc(BizNotice::getCreateTime)
                .last("limit 50")).stream().map(this::toView).toList();
    }

    public List<NoticeView> pendingCalls() {
        return notices.selectList(Wrappers.<BizNotice>lambdaQuery()
                .eq(BizNotice::getChannel, com.wherelee.cabinet.notify.api.NoticeChannel.PHONE_CALL.name())
                .isNull(BizNotice::getDoneAt)
                .orderByAsc(BizNotice::getCreateTime)
                .last("limit 200")).stream().map(this::toView).toList();
    }

    @Transactional
    public void markRead(Long id, Long customerId) {
        if (notices.markReadForCustomer(id, customerId) == 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "这条消息不属于当前账号，或已读过");
        }
    }

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

    private NoticeView toView(BizNotice n) {
        return new NoticeView(n.getId(), n.getChannel(), n.getKind(), n.getContent(), n.getOrderNo(),
                n.getWorkOrderId(), n.getCreateTime(), n.getDoneAt(), n.getDoneNote());
    }
}
