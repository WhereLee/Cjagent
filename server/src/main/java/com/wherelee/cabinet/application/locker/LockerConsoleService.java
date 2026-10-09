package com.wherelee.cabinet.application.locker;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.device.DeviceCommandService;
import com.wherelee.cabinet.application.storage.CompartmentStateService;
import com.wherelee.cabinet.application.storage.ItemDisputeService;
import com.wherelee.cabinet.application.storage.StorageOrderService;
import com.wherelee.cabinet.application.support.PageSupport;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizItemEvidence;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizItemEvidenceMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizLockerConsoleMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
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
public class LockerConsoleService {

    private static final Logger log = LoggerFactory.getLogger(LockerConsoleService.class);

    /** 台账允许的排序列：只放开这 3 个，其余一律拒绝（PageSupport 的白名单约定）。 */
    private static final Set<String> LEDGER_SORTABLE = Set.of("anomalyAt", "stuckMinutes", "slotNo");

    private final BizLockerConsoleMapper consoleMapper;
    private final BizCompartmentMapper slotMapper;
    private final BizItemEvidenceMapper evidenceMapper;
    private final BizCabinetMapper cabinetMapper;
    private final BizStorageOrderMapper orderMapper;
    private final com.wherelee.cabinet.infrastructure.mapper.BizCustomerMapper customerMapper;
    private final CompartmentStateService states;
    private final ItemDisputeService dispute;
    private final StorageOrderService orders;
    private final DeviceCommandService deviceCommands;

    public LockerConsoleService(BizLockerConsoleMapper consoleMapper, BizCompartmentMapper slotMapper,
                                BizItemEvidenceMapper evidenceMapper, BizCabinetMapper cabinetMapper,
                                BizStorageOrderMapper orderMapper, com.wherelee.cabinet.infrastructure.mapper.BizCustomerMapper customerMapper,
                                CompartmentStateService states,
                                ItemDisputeService dispute, StorageOrderService orders,
                                DeviceCommandService deviceCommands) {
        this.consoleMapper = consoleMapper;
        this.slotMapper = slotMapper;
        this.evidenceMapper = evidenceMapper;
        this.cabinetMapper = cabinetMapper;
        this.orderMapper = orderMapper;
        this.customerMapper = customerMapper;
        this.states = states;
        this.dispute = dispute;
        this.orders = orders;
        this.deviceCommands = deviceCommands;
    }

    // ------------------------------------------------------------------ 读

    /** 异常格口台账：默认按判定时刻升序，卡最久的排前面（排班的依据是"卡了多久"）。 */
    public PageResult<LedgerView> anomalies(PageQuery query, String anomaly, Long siteId) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<BizLockerConsoleMapper.LedgerRow> page =
                PageSupport.toPage(query, LEDGER_SORTABLE);
        return PageSupport.toResult(consoleMapper.pageAnomalies(page, anomaly, siteId), LedgerView::from);
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
        BizStorageOrder order = slot.getCurrentOrderId() == null
                ? null : orderMapper.selectById(slot.getCurrentOrderId());
        return new Detail(slot, evidenceMapper.timelineOf(compartmentId, 30), order);
    }

    /** 客户欠费合计：与下单拦截用<b>同一条 SQL</b>，避免"后台显示没欠、下单却被拒"这类两套口径。 */
    public long arrearsOf(Long customerId) {
        return customerId == null ? 0L : consoleMapper.sumCustomerArrears(customerId);
    }

    // ------------------------------------------------------------------ 写

    /**
     * 人工清柜：把异常格口标回正常。两道硬前置——<b>门必须已关</b>（门关着却标异常会永久锁死这一格）、
     * <b>必须留说明</b>。异常是否还在由条件更新兜住，重复点第二次影响 0 行。
     */
    @Transactional
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
        deviceCommands.dispatch(cabinet, compartmentId, CommandAction.FORCE_OPEN,
                slot.getCurrentOrderId(), requestId);
        // 门真的被开了，所以按"门开着"记一笔：它因此自动不可分配，正是开柜期间想要的状态
        states.markForcedOpen(compartmentId, adminId, reason);
        log.warn("后台强制开柜已下发 cabinetNo={} slotNo={} admin={} 事由={}",
                cabinet.getCabinetNo(), slot.getSlotNo(), adminId, reason);
    }

    /**
     * 人工判定设备误报并免除争议期间费用。四道前置（第一条在 Controller 上判权限）：
     * ① 独立权限点；② 证据齐全（"设备说有" + "AI 说无"两条留底）；③ 单未进终态；④ 全程审计。
     *
     * <p>金额不由调用方传：只按 {@code started_at → dispute_started_at} 结算，
     * 所以这里不存在"免除额度"这种东西。
     */
    @Transactional
    public void waiveDisputeFee(String orderNo) {
        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "寄存单不存在");
        }
        if (order.getStatus().isTerminal()) {
            throw new BizException(ResultCode.BIZ_ERROR, "该单已结束，不能再次免除（重复免除会产生第二笔退还）");
        }
        if (!dispute.hasFalseAlarmVerdict(order)) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "证据不足：必须同时存在设备报有物与 AI 复核判无物两条留底，才能按误报免除");
        }
        orders.waiveFalseAlarm(order.getId());
    }

    /**
     * 禁用 / 启用客户。
     *
     * <p>它只改一个状态位：<b>拦下登录与下单的是底座已有的机制</b>（status=0 时权限装载为空集合
     * → 403），不在这里再造一份检查。这也是为什么这里不需要任何“禁用阈值”。
     *
     * <p>与欠费拦截分工不同，不能合并：欠费拦截是<b>系统自动、补缴即自愈</b>；
     * 禁用是<b>人工判定、人工解除</b>，针对的是拒付与反复恶意行为。取件两边都不拦。
     */
    @Transactional
    public void changeCustomerStatus(Long customerId, Integer status, String reason) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ResultCode.PARAM_INVALID, "状态只能是 1（正常）或 0（禁用）");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID, "禁用或启用必须写明事由（它会进审计）");
        }
        var customer = customerMapper.selectById(customerId);
        if (customer == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "客户不存在");
        }
        if (java.util.Objects.equals(customer.getStatus(), status)) {
            log.info("客户状态本就是该值，幂等返回 customerId={} status={}", customerId, status);
            return;
        }
        customer.setStatus(status);
        if (customerMapper.updateById(customer) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "客户信息已被并发修改，请重试 customerId=" + customerId);
        }
        log.warn("客户状态变更 customerId={} status={} 事由={}", customerId, status, reason);
    }

    /** 格口详情出参。 */
    public record Detail(BizCompartment compartment, List<BizItemEvidence> evidence, BizStorageOrder currentOrder) {
    }

    /**
     * 台账行视图。<b>接口层不得直接用 mapper 的行对象</b>（ArchUnit 有这条规则，今天就是它拦下来的）：
     * 行对象跟着 SQL 列变，一变就把 SQL 细节透给了前端契约。
     */
    public record LedgerView(Long compartmentId, String cabinetNo, String slotNo, String anomaly,
                             LocalDateTime anomalyAt, Long stuckMinutes, String anomalyReason,
                             String siteName, Long currentOrderId) {

        static LedgerView from(BizLockerConsoleMapper.LedgerRow row) {
            return new LedgerView(row.getId(), row.getCabinetNo(), row.getSlotNo(),
                    row.getAnomaly() == null ? null : row.getAnomaly().name(),
                    row.getAnomalyAt(), row.getStuckMinutes(), row.getAnomalyReason(),
                    row.getSiteName(), row.getCurrentOrderId());
        }
    }

    /** 积压概览行。 */
    public record SummaryView(String anomaly, long rowsCount, long longestMinutes) {
    }

    /** 未退押金行。 */
    public record DepositView(Long depositId, String orderNo, Long customerId, Long points,
                              String depositStatus, Long heldHours) {
    }

    /** 欠费客户行。 */
    public record ArrearsView(Long customerId, Long arrearsPoints, int arrearsOrders,
                              LocalDateTime lastFinishedAt) {
    }
}
