package com.wherelee.cabinet.servicedesk.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.enums.CloseReason;
import com.wherelee.cabinet.servicedesk.api.ActorType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderCsatPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderStatus;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrderCsat;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderCsatMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderCsatService implements WorkOrderCsatPort {

    private final BizWorkOrderCsatMapper csats;
    private final WorkOrderJournal journal;

    public WorkOrderCsatService(BizWorkOrderCsatMapper csats, WorkOrderJournal journal) {
        this.csats = csats;
        this.journal = journal;
    }

    @Override
    @Transactional
    public CsatView submit(Long customerId, Long workOrderId, int score, String comment) {
        if (score < 1 || score > 5) {
            throw new BizException(ResultCode.PARAM_INVALID, "满意度只能是 1–5 星");
        }
        BizWorkOrder order = journal.require(workOrderId);
        if (customerId == null || !customerId.equals(order.getCustomerId())) {
            throw new BizException(ResultCode.FORBIDDEN, "这张工单不属于当前账号");
        }
        if (order.getStatus() != WorkOrderStatus.RESOLVED
                || order.getCloseReason() != CloseReason.USER_CONFIRMED) {
            throw new BizException(ResultCode.BIZ_ERROR, "只有你确认已解决的工单可以评价");
        }
        String note = comment == null || comment.isBlank() ? null : comment.trim();
        if (note != null && note.length() > 300) {
            throw new BizException(ResultCode.PARAM_INVALID, "备注最多 300 字");
        }

        BizWorkOrderCsat csat = new BizWorkOrderCsat();
        csat.setWorkOrderId(workOrderId);
        csat.setCustomerId(customerId);
        csat.setScore(score);
        csat.setComment(note);
        try {
            csats.insert(csat);
        } catch (DuplicateKeyException already) {
            throw new BizException(ResultCode.BIZ_ERROR, "这张工单已经评价过了");
        }
        journal.note(order, "CSAT_SUBMITTED", customerId, "满意度：" + score + " 星",
                ActorType.USER.name(), null);
        return new CsatView(workOrderId, score, note, csat.getCreateTime());
    }

    @Override
    public CsatView byOrder(Long workOrderId) {
        BizWorkOrderCsat csat = csats.selectOne(Wrappers.<BizWorkOrderCsat>lambdaQuery()
                .eq(BizWorkOrderCsat::getWorkOrderId, workOrderId));
        return csat == null ? null
                : new CsatView(workOrderId, csat.getScore(), csat.getComment(), csat.getCreateTime());
    }
}
