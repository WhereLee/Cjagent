package com.wherelee.cabinet.locker.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.device.api.CommandPort;
import com.wherelee.cabinet.locker.app.CompartmentStateService;
import com.wherelee.cabinet.device.api.EvidencePort;
import com.wherelee.cabinet.device.api.EvidenceSnapshot;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 运营后台（第 14 刀）：台账读 + 现场处置写。
 *
 * <p>这一层的定位是<b>"把前几刀记下来的东西变成可操作的对象"</b>：门态与异常（13B）、
 * 证据与争议（13C）都只做到"记下来 + 拦住"，没人处理就等于没闭环。
 *
 * <p>三个刻意的设计：
 * <ul>
 *   <li><b>处置动作不新增任何额度/阈值参数</b>：动钱的免除由算法定金额、由证据定可否，
 *       操作者没有填数字的入口，所以不再叠一层额度校验（docs/架构约定.md §9）；</li>
 *   <li><b>解除异常必须留说明</b>：那句话是"人来看过"的唯一凭据，没它就等于一次无据可查的状态修改；</li>
 *   <li><b>强制开柜要二次确认</b>：它是"单已结束后取回物品"的唯一出口（I13），
 *       开的是可能留着别人物品的格子。</li>
 * </ul>
 *
 * <p>权限判定不在这里：全部在 Controller 上用 {@code hasAuthority}。本类只做业务前置校验，
 * 两层各管一件事，才不会漏出"只有绕过 HTTP 直调 service 才能免单"的口子。
 */
@Service
public class LockerConsoleService implements com.wherelee.cabinet.locker.api.LockerConsolePort {

    private static final Logger log = LoggerFactory.getLogger(LockerConsoleService.class);

    /** 台账允许的排序列：只放开这 3 个，其余一律拒绝（PageSupport 的白名单约定）。 */
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

    // ------------------------------------------------------------------ 读

    /** 异常格口台账：默认按判定时刻升序，卡最久的排前面（排班的依据是"卡了多久"）。 */
    public PageResult<LedgerView> anomalies(PageQuery query, String anomaly, Long siteId) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<BizLockerConsoleMapper.LedgerRow> page =
                PageSupport.toPage(query, LEDGER_SORTABLE);
        return PageSupport.toResult(consoleMapper.pageAnomalies(page, anomaly, siteId), this::toLedger);
    }

    /** 按原因码的积压概览（后台顶部那一排数字）。 */
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

    /** 格口详情：现状 + 证据时间线 + 当前占用的单。判"能不能清、该不该免"看的就是这条。 */
    public Detail compartmentDetail(Long compartmentId) {
        BizCompartment slot = slotMapper.selectById(compartmentId);
        if (slot == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "格口不存在");
        }
        OrderSnapshot current = slot.getCurrentOrderId() == null
                ? null : orderQueries.byId(slot.getCurrentOrderId()).orElse(null);
        return new Detail(slot, evidencePort.timelineOfCompartment(compartmentId, 30), current);
    }

    /** 客户欠费合计：与下单拦截用<b>同一条 SQL</b>，避免"后台显示没欠、下单却被拒"这类两套口径。 */
    public long arrearsOf(Long customerId) {
        return customerId == null ? 0L : consoleMapper.sumCustomerArrears(customerId);
    }

    // ------------------------------------------------------------------ 写

    /**
     * 人工清柜：把异常格口标回正常。三道硬前置——<b>门必须已关</b>（门关着却标异常会永久锁死这一格）、
     * <b>必须留说明</b>、<b>必须拿到“柜内已空”的可靠凭据</b>（第 21 刀安全闸：之前只查门不查物，
     * 运维关上门点清柜就能把还放着东西的格子放回可售池——那正是把别人的东西卖给下一位）。
     * 异常是否还在由条件更新兜住，重复点第二次影响 0 行。
     *
     * <p>故意不开事务：物检（sense）是一次设备问答，不能握着数据库连接等它（第 9/11 刀同一纪律）；
     * clearAnomaly 与 recordManualRecovery 各自成短事务，两者无需原子绑定。
     */
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

    /**
     * 后台强制开柜：二次确认 + 事由进审计。<b>不加"每人每日 N 次"这类配额参数</b>——
     * 防滥用靠权限点、审计与看板（架构约定 §9），不是给业务规则再叠一层数字。
     */
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
        // 门真的被开了，所以按"门开着"记一笔：它因此自动不可分配，正是开柜期间想要的状态
        states.markForcedOpen(compartmentId, adminId, reason);
        log.warn("后台强制开柜已下发 cabinetNo={} slotNo={} admin={} 事由={}",
                cabinet.getCabinetNo(), slot.getSlotNo(), adminId, reason);
    }

    // waiveDisputeFee（后台人工判定误报并免除争议费用）已删除（2026-10-12 用户令）。
    // 它要免的那笔钱随着 19A“结束不拦人”一起消失了：不存在“平台等判定期间”这回事。
    // 原来四道前置里的三条（独立权限点、已终态不可重复、全程审计）仍然是通用做法，
    // 下一切后台动钱的动作都得照它们办。

    private LedgerView toLedger(BizLockerConsoleMapper.LedgerRow row) {
        return new LedgerView(row.getId(), row.getCabinetId(), row.getCabinetNo(), row.getSlotNo(),
                row.getAnomaly() == null ? null : row.getAnomaly().name(),
                row.getAnomalyAt(), row.getStuckMinutes(), row.getAnomalyReason(),
                row.getSiteId(), row.getSiteName(), row.getCurrentOrderId());
    }

}
