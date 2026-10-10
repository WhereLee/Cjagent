package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.billing.PriceRuleService;
import com.wherelee.cabinet.application.billing.PriceRuleService;
import com.wherelee.cabinet.application.point.PointAccountService;
import com.wherelee.cabinet.application.storage.StorageOrderService;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizPriceRule;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.OnlineState;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizSiteMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计价策略发布与按点位灰度（第 14D 刀验收）。
 *
 * <p>这一刀真正要证的只有一句话：<b>改价不能追溯改变已经下出去的单</b>。
 * 所以每条用例都在看两处——新单冻结了多少、快照里留了哪一版；
 * 而不是"发布接口能调通"（那没有价值）。
 *
 * <p>默认价（yml）：SMALL 15 点/小时、押金 200、免费 10 分钟。用例里发布的策略刻意用不同的数字，
 * 好让"用的是哪一套"一眼可辨。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.hold-grace-minutes=0",
})
@DisplayName("计价策略：全局默认价、按点位灰度、预约生效、回滚不追溯")
class PriceRuleFlowTest {

    private static final Long TENANT = 8121L;
    private static final Long ADMIN = 555L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private BizSiteMapper siteMapper;
    @Autowired
    private BizCabinetMapper cabinetMapper;
    @Autowired
    private BizCompartmentMapper slotMapper;
    @Autowired
    private BizStorageOrderMapper orderMapper;
    @Autowired
    private PointAccountService points;
    @Autowired
    private StorageOrderService orderService;
    @Autowired
    private PriceRuleService priceRules;

    private JdbcTemplate jdbc;
    private Long siteA;
    private Long siteB;
    private String cabinetA;
    private String cabinetB;
    private final Set<Long> customerIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        TenantContext.runAs(TENANT, () -> {
            BizSite a = site("SA-" + suffix(), "灰度点位");
            BizSite b = site("SB-" + suffix(), "对照点位");
            siteA = a.getId();
            siteB = b.getId();
            cabinetA = cabinet(a, "CAB-A-");
            cabinetB = cabinet(b, "CAB-B-");
        });
        clearRules();
    }

    @AfterEach
    void cleanup() {
        Object[] customers = customerIds.toArray();
        jdbc.update("delete from biz_delay_task where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_fault_event where cabinet_id in (select id from biz_cabinet where tenant_id = ?)", TENANT);
        // 不建客户的用例（比如只看预览价）会让 in (?) 没有参数可绑，报
        // “No value specified for parameter 1”：这类删除必须先判空再拼占位符
        if (!customerIds.isEmpty()) {
            jdbc.update("delete from biz_point_txn where customer_id in (" + placeholders() + ")", customers);
            jdbc.update("delete from biz_point_account where customer_id in (" + placeholders() + ")", customers);
            // 很关键：不删客户就等于每次跑完在共享测试库里沉积一批孤儿账号（前一版本就是这样）
            jdbc.update("delete from biz_customer where id in (" + placeholders() + ")", customers);
        }
        jdbc.update("delete from biz_deposit where order_id in (select id from biz_storage_order where cabinet_id in (select id from biz_cabinet where tenant_id = ?))", TENANT);
        jdbc.update("delete from biz_storage_order where cabinet_id in (select id from biz_cabinet where tenant_id = ?)", TENANT);
        jdbc.update("delete from biz_compartment where cabinet_id in (select id from biz_cabinet where tenant_id = ?)", TENANT);
        jdbc.update("delete from biz_cabinet where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_site where tenant_id = ?", TENANT);
        clearRules();
        customerIds.clear();
        TenantContext.clear();
    }

    private void clearRules() {
        jdbc.update("delete from biz_price_rule where tenant_id = ?", TENANT);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** MySQL JSON 列会重排空白，比对内容前先剔掉 */
    private static String noSpace(String json) {
        return json == null ? "" : json.replaceAll("\\s", "");
    }

    private String placeholders() {
        return customerIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("?");
    }

    private BizSite site(String code, String name) {
        BizSite site = new BizSite();
        site.setSiteCode(code);
        site.setName(name);
        site.setCategory("MALL");
        site.setStatus(1);
        siteMapper.insert(site);
        return site;
    }

    private String cabinet(BizSite site, String prefix) {
        String no = prefix + suffix();
        BizCabinet cabinet = new BizCabinet();
        cabinet.setSiteId(site.getId());
        cabinet.setCabinetNo(no);
        cabinet.setName("策略柜机");
        cabinet.setModelId(90001L);
        cabinet.setCabinetStatus(CabinetStatus.ENABLED);
        cabinet.setOnlineState(OnlineState.ONLINE);
        cabinetMapper.insert(cabinet);
        for (SizeType size : SizeType.values()) {
            for (int i = 1; i <= 3; i++) {
                BizCompartment slot = new BizCompartment();
                slot.setCabinetId(cabinet.getId());
                slot.setSlotNo(size.name().charAt(0) + String.format("%02d", i));
                slot.setSizeType(size);
                slot.setStatus(SlotStatus.FREE);
                slotMapper.insert(slot);
            }
        }
        return no;
    }

    private Long newCustomer() {
        Long id = 995000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 40000);
        customerIds.add(id);
        TenantContext.runAs(TENANT, () -> points.recharge(id, 20000L, "CHG-" + UUID.randomUUID(), "策略用例 funding"));
        return id;
    }

    /** 草稿：小格口 20 点/小时、押金 300，其余与默认一致。*/
    private BizPriceRule draft(long smallUnit, long deposit) {
        BizPriceRule rule = new BizPriceRule();
        rule.setDepositPoints(deposit);
        rule.setFreeMinutes(10);
        rule.setDailyCapHours(12);
        rule.setCapDays(3);
        rule.setToleranceMinutes(5);
        rule.setRemoteCloseHours(2);
        rule.setUnitSmall(smallUnit);
        rule.setUnitMedium(smallUnit + 10);
        rule.setUnitLarge(smallUnit + 25);
        return rule;
    }

    private BizStorageOrder createOrder(String cabinetNo) {
        Long customerId = newCustomer();
        String orderNo = TenantContext.callAs(TENANT, () -> orderService.create(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))).orderNo();
        return TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, orderNo)));
    }

    @Test
    @DisplayName("预览：缺参数直接拒（不拆箱 null 算价），完整草稿的价与结算口径一致")
    void previewRejectsHalfFilledDraftAndMatchesSettlement() {
        // 实测发现的老 bug：表单填一半就预览会把 500 报给用户（null 拆箱）。
        // 这里只缺单价一项，所以报错必须精确指到“小格口单价”，而不是泛泛一句“参数不正确”
        BizPriceRule half = draft(20L, 300L);
        half.setUnitSmall(null);
        BizException rejected = assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> priceRules.preview(siteA, half)));
        assertEquals(ResultCode.PARAM_INVALID, rejected.getResultCode());
        assertTrue(rejected.getMessage().contains("小格口单价"),
                "报错要说清缺哪一项参数：" + rejected.getMessage());

        var full = TenantContext.callAs(TENANT, () -> priceRules.preview(siteA, draft(20L, 300L)));
        assertEquals("draft", full.get("source"), "草稿预览不能伪装成已存在的版本");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> samples = (java.util.Map<String, Object>) full.get("samples");
        // SMALL @ 90min：减 10 分钟免费窗口后剩 80 分钟→ 2 个计费小时 × 20 = 40
        assertEquals(40L, samples.get("SMALL@90min"), "预览价必须与结算同一算法");
        // LARGE @ 4800min（80 小时）：封顶 3 天 × 每日 12 小时 = 36 小时；draft 里 large = small+25 = 45
        // → 36 × 45 = 1620。预览也要把阶梯封顶算进去，否则页面会承诺一个收不到的数
        assertEquals(1620L, samples.get("LARGE@4800min"));
    }

    @Test
    @DisplayName("没发布过策略时吃配置默认价；发布后新单按策略价，快照里留下版本号")
    void defaultPriceThenPublishedPrice() {
        BizStorageOrder before = createOrder(cabinetA);
        // 默认价（测试库配置）：小格 15 点/小时，预估 60 分 → 1 小时 = 15；
        // 账户押金不冻在单上（定-1），所以冻结额里不再有押金这一项
        assertEquals(15L, before.getFrozenPoints(), "未发布策略时必须是配置默认价");
        // 列是 MySQL JSON 类型，存取会被重排成 `"key": 15`（冒号后多一个空格），
        // 所以比对先把空白剔掉——断言要盯的是内容，不是格式化细节
        assertTrue(noSpace(before.getPricingSnapshot()).contains("unitPointsPerHour\":15"),
                before.getPricingSnapshot());

        TenantContext.runAs(TENANT, () -> priceRules.publish(draft(20L, 300L), null,
                LocalDateTime.now(), "双十一前统一调价", ADMIN));

        BizStorageOrder after = createOrder(cabinetA);
        // 策略价：小格 20 点/小时 × 1 小时（押金已移到账户栅，不再计入冻结）
        assertEquals(20L, after.getFrozenPoints(), "新单必须按刚发布的策略价冻结");
        assertTrue(noSpace(after.getPricingSnapshot()).contains("priceRuleVersion"), "快照要能回答这单按哪一版算的");
        // 回看上一张：它的快照与冻结额都没被新策略动过
        BizStorageOrder unchanged = TenantContext.callAs(TENANT, () -> orderMapper.selectById(before.getId()));
        assertEquals(15L, unchanged.getFrozenPoints(), "发布不得追溯改变已下出去的单");
    }

    @Test
    @DisplayName("按点位灰度：站点策略只影响它的柜机，别的点位仍走全局价")
    void siteRuleOnlyAffectsItsOwnSite() {
        TenantContext.runAs(TENANT, () -> priceRules.publish(draft(20L, 300L), null,
                LocalDateTime.now(), "全局新价", ADMIN));
        TenantContext.runAs(TENANT, () -> priceRules.publish(draft(40L, 500L), siteA,
                LocalDateTime.now(), "先只灰度 A 点位", ADMIN));

        BizStorageOrder onA = createOrder(cabinetA);
        BizStorageOrder onB = createOrder(cabinetB);

        assertEquals(40L, onA.getFrozenPoints(), "A 点位走站点策略：小格 40 点/小时");
        assertEquals(20L, onB.getFrozenPoints(), "B 点位仍走全局策略，不能被 A 的灰度带过去");

        var rulesA = TenantContext.callAs(TENANT, () -> priceRules.list());
        assertTrue(rulesA.stream().anyMatch(r -> siteA.equals(r.siteId()) && "LIVE".equals(r.status())));
    }

    @Test
    @DisplayName("预约生效：到点前仍用旧价，且旧策略不能被发布动作误标为已替代")
    void scheduledRuleTakesOverOnlyAtItsTime() {
        TenantContext.runAs(TENANT, () -> priceRules.publish(draft(20L, 300L), null,
                LocalDateTime.now(), "现在生效", ADMIN));
        TenantContext.runAs(TENANT, () -> priceRules.publish(draft(60L, 900L), null,
                LocalDateTime.now().plusMinutes(30), "半小时后切换", ADMIN));

        BizStorageOrder stillOld = createOrder(cabinetA);
        assertEquals(20L, stillOld.getFrozenPoints(), "未到生效时刻必须仍用旧价（这是预约发布的全部意义）");

        // 把预约版的生效时刻改为“就是现在”：它比旧版晚，所以到点后该选中它。
        // （不能把它挪到旧版之前——“已到生效时刻者中取最晚”的规则下，旧版赢才是正确行为）
        jdbc.update("update biz_price_rule set effective_at = now(3) "
                + "where tenant_id = ? and unit_small = 60", TENANT);
        BizStorageOrder afterSwitch = createOrder(cabinetA);
        assertEquals(60L, afterSwitch.getFrozenPoints(), "到点后自动切到预约的那版");

        List<PriceRuleService.View> live = TenantContext.callAs(TENANT, () -> priceRules.list()).stream()
                .filter(r -> "LIVE".equals(r.status()) && r.siteId() == null).toList();
        assertEquals(2, live.size(), "旧版仍在 LIVE 里（它只是被时间挤出选择，不该被标成已替代）");
    }

    @Test
    @DisplayName("回滚=再发一版：新单回到旧价，进行中的那张单仍按它当时的快照结算")
    void rollbackRepublishesOldContentWithoutTouchingLiveOrders() {
        TenantContext.runAs(TENANT, () -> priceRules.publish(draft(20L, 300L), null,
                LocalDateTime.now(), "v1", ADMIN));
        BizStorageOrder v1Order = createOrder(cabinetA);
        TenantContext.runAs(TENANT, () -> priceRules.publish(draft(99L, 300L), null,
                LocalDateTime.now(), "v2 发现价配错了", ADMIN));

        List<PriceRuleService.View> v1 = TenantContext.callAs(TENANT, () -> priceRules.list()).stream()
                .filter(r -> r.unitSmall() == 20L).toList();
        assertEquals(1, v1.size());
        TenantContext.runAs(TENANT, () -> priceRules.rollback(v1.get(0).id(), "灰度失败，回退", ADMIN));

        BizStorageOrder afterRollback = createOrder(cabinetA);
        assertEquals(20L, afterRollback.getFrozenPoints(), "回滚后新单应回到 v1 的价");

        // 进行中的单不受影响：它下单时抄的快照仍是 v1
        BizStorageOrder untouched = TenantContext.callAs(TENANT, () -> orderMapper.selectById(v1Order.getId()));
        assertEquals(20L, untouched.getFrozenPoints(), "发布 v2 与回滚都不能回头改这张单的冻结额");
        assertTrue(noSpace(untouched.getPricingSnapshot()).contains("unitPointsPerHour\":20"),
                "快照里的单价必须还是 v1 的 20 点：结算读快照而不读策略表");
    }
}
