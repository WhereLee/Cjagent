package com.wherelee.cabinet.locker.app;

import com.wherelee.cabinet.device.api.CommandPort;
import com.wherelee.cabinet.locker.app.CompartmentStateService;
import com.wherelee.cabinet.device.api.EvidencePort;
import com.wherelee.cabinet.storage.api.OrderQueryPort;
import com.wherelee.cabinet.storage.api.OrderSnapshot;
import com.wherelee.cabinet.platform.web.PageSupport;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import com.wherelee.cabinet.locker.infra.BizLockerConsoleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class LockerConsoleService implements com.wherelee.cabinet.locker.api.LockerConsolePort {

    private static final Logger log = LoggerFactory.getLogger(LockerConsoleService.class);

    private static final Set<String> LEDGER_SORTABLE = Set.of("anomalyAt", "stuckMinutes", "slotNo");

    private final BizLockerConsoleMapper consoleMapper;
    private final BizCompartmentMapper slotMapper;
    private final EvidencePort evidencePort;
    private final BizCabinetMapper cabinetMapper;
    private final OrderQueryPort orderQueries;
    private final CompartmentStateService states;
    private final CommandPort deviceCommands;

    public LockerConsoleService(BizLockerConsoleMapper consoleMapper, BizCompartmentMapper slotMapper,
                                EvidencePort evidencePort, BizCabinetMapper cabinetMapper,
                                OrderQueryPort orderQueries,
                                CompartmentStateService states,
                                CommandPort deviceCommands) {
        this.consoleMapper = consoleMapper;
        this.slotMapper = slotMapper;
        this.evidencePort = evidencePort;
        this.cabinetMapper = cabinetMapper;
        this.orderQueries = orderQueries;
        this.states = states;
        this.deviceCommands = deviceCommands;
    }

    public PageResult<LedgerView> anomalies(PageQuery query, String anomaly, Long siteId) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<BizLockerConsoleMapper.LedgerRow> page =
                PageSupport.toPage(query, LEDGER_SORTABLE);
        return PageSupport.toResult(consoleMapper.pageAnomalies(page, anomaly, siteId), this::toLedger);
    }

    public List<SummaryView> anomalySummary(Long siteId) {
        return consoleMapper.summarizeAnomalies(siteId).stream()
                .map(s -> new SummaryView(s.getAnomaly(), s.getRowsCount(), s.getLongestMinutes()))
                .toList();
    }

    public PageResult<DepositView> unrefundedDeposits(PageQuery query) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<BizLockerConsoleMapper.UnrefundedDeposit> page =
                PageSupport.toPage(query, Set.of());
        return PageSupport.toResult(consoleMapper.pageUnrefundedDeposits(page),
                d -> new DepositView(d.getDepositId(), d.getOrderNo(), d.getCustomerId(), d.getPoints(),
                        d.getDepositStatus(), d.getHeldHours()));
    }

    public PageResult<ArrearsView> arrears(PageQuery query) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<BizLockerConsoleMapper.ArrearsRow> page =
                PageSupport.toPage(query, Set.of());
        return PageSupport.toResult(consoleMapper.pageArrears(page),
                a -> new ArrearsView(a.getCustomerId(), a.getArrearsPoints(), a.getArrearsOrders(),
                        a.getLastFinishedAt()));
    }

    public Detail compartmentDetail(Long compartmentId) {
        BizCompartment slot = slotMapper.selectById(compartmentId);
        if (slot == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "格口不存在");
        }
        OrderSnapshot current = slot.getCurrentOrderId() == null
                ? null : orderQueries.byId(slot.getCurrentOrderId()).orElse(null);
        return new Detail(slot, evidencePort.timelineOfCompartment(compartmentId, 30), current);
    }

    public long arrearsOf(Long customerId) {
        return customerId == null ? 0L : consoleMapper.sumCustomerArrears(customerId);
    }

    public void resolveAnomaly(Long compartmentId, String note) {
        if (note == null || note.isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID, "清除异常必须填写现场说明（看到了什么、如何处理）");
        }
        BizCompartment slot = slotMapper.selectById(compartmentId);
        if (slot == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "格口不存在");
        }
        if (slot.getAnomaly() == null) {
            log.info("格口本就无异常，幂等返回 compartmentId={}", compartmentId);
            return;
        }
        if (slot.doorOpen()) {
            throw new BizException(ResultCode.BIZ_ERROR, "柜门尚未关闭，不能标为正常：请先关好门再清柜");
        }
        var sensing = states.sense(slot.getCabinetId(), compartmentId);
        if (!sensing.doorClosed() || sensing.presence() != Presence.ABSENT || !states.fresh(sensing)) {
            throw new BizException(ResultCode.BIZ_ERROR, "柜内检测到物品或无法确认已空：不能解除异常"
                    + "（物主自助取回后会自动恢复，或现场确认已清空后再清柜）");
        }
        if (slotMapper.clearAnomaly(compartmentId) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "异常已被他人处理，请刷新后重试 compartmentId=" + compartmentId);
        }
        states.recordManualRecovery(compartmentId, note);
        log.info("人工清柜完成 compartmentId={} slotNo={} 说明={}", compartmentId, slot.getSlotNo(), note);
    }

    @Transactional
    public void forceOpen(Long compartmentId, boolean confirmed, String reason, Long adminId) {
        if (!confirmed) {
            throw new BizException(ResultCode.BIZ_ERROR, "强制开柜需二次确认：请勾选确认（格口内可能留有他人物品）");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID, "强制开柜必须填写事由，它会进审计");
        }
        BizCompartment slot = slotMapper.selectById(compartmentId);
        if (slot == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "格口不存在");
        }
        BizCabinet cabinet = cabinetMapper.selectById(slot.getCabinetId());
        if (cabinet == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "柜机不存在，无法强制开柜 cabinetId=" + slot.getCabinetId());
        }
        String requestId = "adm-" + adminId + "-" + compartmentId + "-" + java.util.UUID.randomUUID();
        deviceCommands.dispatch(cabinet.getId(), compartmentId, CommandAction.FORCE_OPEN,
                slot.getCurrentOrderId(), requestId);
        states.markForcedOpen(compartmentId, adminId, reason);
        log.warn("后台强制开柜已下发 cabinetNo={} slotNo={} admin={} 事由={}",
                cabinet.getCabinetNo(), slot.getSlotNo(), adminId, reason);
    }

    private LedgerView toLedger(BizLockerConsoleMapper.LedgerRow row) {
        return new LedgerView(row.getId(), row.getCabinetId(), row.getCabinetNo(), row.getSlotNo(),
                row.getAnomaly() == null ? null : row.getAnomaly().name(),
                row.getAnomalyAt(), row.getStuckMinutes(), row.getAnomalyReason(),
                row.getSiteId(), row.getSiteName(), row.getCurrentOrderId());
    }

}
