package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.storage.AsyncStorageOrderAppService;
import com.wherelee.cabinet.application.storage.message.OrderPersistMessage;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.OnlineState;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import com.wherelee.cabinet.infrastructure.alloc.SlotPreDeductionService;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预扣 + 异步落库链路（strategy=prealloc）的业务不变量。
 *
 * <p><b>用例一律不发消息、也不调 createAsync</b>：那会真的投递到 broker，而同一 JVM 里的消费者
 * 容器随即消费它，于是"我手工调 persistFromMessage"变成第二次投递——先消费的一方把幂等键标成
 * PROCESSED，用例就在断言"什么都没发生"（单跑通过、连跑失败，正是这种竞态的形状）。
 * 所以这里<b>直接预扣 + 手工构造消息</b>，只测幂等、赛跑、归属、失败上抛这四件事。
 *
 * <p>请求路径（createAsync → broker → 消费者 → 落库）由<b>真实 HTTP 手工验证</b>负责，
 * 结果与口径写在 docs/压测报告-格口分配.md：它是环境相关的链路确证，不适合当业务门禁。
 *
 * <p>本类不加 @Transactional（测试事务不传播到被调方法的独立事务，只会制造"以为回滚了"的错觉），
 * 数据由 @AfterEach 精确物理清理。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = "cabinet.alloc.strategy=prealloc")
class AsyncPersistFlowTest {

    private static final Long TENANT = 8101L;

    @Autowired
    private AsyncStorageOrderAppService asyncService;
    @Autowired
    private com.wherelee.cabinet.application.point.PointAccountService points;
    @Autowired
    private SlotPreDeductionService preDeduction;
    @Autowired
    private BizSiteMapper siteMapper;
    @Autowired
    private BizCabinetMapper cabinetMapper;
    @Autowired
    private BizCompartmentMapper slotMapper;
    @Autowired
    private BizStorageOrderMapper orderMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Long cabinetId;
    private String cabinetNo;
    private final List<String> holdKeys = new ArrayList<>();

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        cabinetNo = "CAB-A-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("S-" + cabinetNo);
            site.setName("异步用例点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("异步用例柜机");
            cabinet.setModelId(90001L);
            cabinet.setCabinetStatus(CabinetStatus.ENABLED);
            cabinet.setOnlineState(OnlineState.ONLINE);
            cabinetMapper.insert(cabinet);
            cabinetId = cabinet.getId();

            for (SizeType size : SizeType.values()) {
                for (int i = 1; i <= 3; i++) {
                    BizCompartment slot = new BizCompartment();
                    slot.setCabinetId(cabinetId);
                    slot.setSlotNo(size.name().charAt(0) + String.format("%02d", i));
                    slot.setSizeType(size);
                    slot.setStatus(SlotStatus.FREE);
                    slotMapper.insert(slot);
                }
            }
        });
        // 预扣的空闲集合必须来自 DB 真相，这是这套模型的前提。
        // syncFreeSets 内部要查库，所以同样必须包在租户上下文里（租户守卫会拒无上下文查询）。
        TenantContext.runAs(TENANT, () -> asyncService.syncFreeSets(cabinetId));
    }

    @AfterEach
    void cleanup() {
        holdKeys.forEach(no -> redis.delete("cab:alloc:hold:" + no));
        holdKeys.clear();
        for (SizeType size : SizeType.values()) {
            redis.delete("cab:alloc:free:" + cabinetId + ":" + size.name());
        }
        jdbc.update("delete from biz_msg_consume where tenant_id = ? and msg_key like 'SO%'", TENANT);
        // 先清引用订单的流水与押金，再删订单（顺序反了子查询查不到，残留会污染下个用例）
        jdbc.update("delete from biz_point_txn where ref_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_deposit where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "S-" + cabinetNo);
        TenantContext.clear();
    }

    /**
     * 只做预扣（不发消息），返回一条待落库消息。
     * 顺序与生产一致：SPOP 弹出格口 → 本该由 publisher 投递，这里由测试自己持有消息。
     */
    private OrderPersistMessage preDeductOnly(Long customerId, SizeType required) {
        Long orderId = IdWorker.getId();
        String orderNo = "SO" + orderId;
        holdKeys.add(orderNo);

        // 消费者落库时要冻押金与预估，没钱的账户会让落库失败（这不是链路 bug，是用例缺前提）
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 20000L,
                "FUND-" + orderNo, "异步用例 funding"));

        var deducted = TenantContext.callAs(TENANT, () -> preDeduction.tryPreDeduct(
                cabinetId, SizeType.acceptanceOrder(required), orderNo, Duration.ofSeconds(180), customerId));
        assertNotNull(deducted, "3 个空闲格口应当能预扣成功");

        return new OrderPersistMessage(orderNo, orderId, TENANT, customerId, cabinetId,
                deducted.slotId(), deducted.size().name(), "V" + Math.abs(orderId % 1000000),
                60, "{\"strategy\":\"placeholder\"}", "req-" + orderId, "trace-" + orderNo);
    }

    @Test
    @DisplayName("落库消费者写入订单：格口 RESERVED、hold 被提交清掉、集合不虚高")
    void persistWritesOrder() {
        OrderPersistMessage message = preDeductOnly(910001L, SizeType.SMALL);

        asyncService.persistFromMessage(message);

        BizStorageOrder order = TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, message.orderNo())));
        assertNotNull(order, "消费后订单行必须存在");
        assertEquals("RESERVED", order.getStatus().name());
        assertEquals(1, order.getActiveFlag(), "活动单必须占住唯一索引位");

        BizCompartment slot = TenantContext.callAs(TENANT, () -> slotMapper.selectById(message.slotId()));
        assertEquals(SlotStatus.RESERVED, slot.getStatus(), "格口未置为占用");
        assertEquals(order.getId(), slot.getCurrentOrderId());

        assertFalse(preDeduction.hasHold(message.orderNo()), "提交后 hold 标记应被删除");
        // 只占了 1 个小格口（共 3 个）：COMMIT 的意义是"不回集合"，但也不能弄丢别的空闲成员
        assertEquals(2, preDeduction.freeCount(cabinetId, SizeType.SMALL),
                "提交不得把格口放回空闲集合，也不得弄丢其它空闲成员");

        String status = jdbc.queryForObject("select status from biz_msg_consume where msg_key = ?",
                String.class, message.orderNo());
        assertEquals("PROCESSED", status, "幂等记录要落在 PROCESSED，重投才有依据");
    }

    @Test
    @DisplayName("重复投递只落一行：幂等表 + 订单号唯一索引双保险")
    void duplicateDeliveryIsIdempotent() {
        OrderPersistMessage message = preDeductOnly(910002L, SizeType.SMALL);
        asyncService.persistFromMessage(message);

        // 第二次投递（模拟 broker 重投）：hold 已被提交删掉，走"占位已释放"分支跳过
        asyncService.persistFromMessage(message);

        Integer count = jdbc.queryForObject("select count(*) from biz_storage_order where order_no = ?",
                Integer.class, message.orderNo());
        assertEquals(1, count, "重复投递不得产生第二张订单");

        Integer consumeRows = jdbc.queryForObject("select count(*) from biz_msg_consume where msg_key = ?",
                Integer.class, message.orderNo());
        assertEquals(1, consumeRows, "幂等记录也不得重复");
    }

    @Test
    @DisplayName("取消先于落库：归还格口，随后到达的消息不落库")
    void cancelBeforePersistReleasesSlot() {
        OrderPersistMessage message = preDeductOnly(910003L, SizeType.SMALL);
        assertEquals(2, preDeduction.freeCount(cabinetId, SizeType.SMALL), "预扣后小格口应剩 2");

        assertTrue(asyncService.cancelQueued(message.orderNo(), 910003L), "排队中的占位应可取消");
        assertEquals(3, preDeduction.freeCount(cabinetId, SizeType.SMALL), "取消必须把格口放回空闲集合");

        asyncService.persistFromMessage(message);

        Integer rows = jdbc.queryForObject("select count(*) from biz_storage_order where order_no = ?",
                Integer.class, message.orderNo());
        assertEquals(0, rows, "占位已释放，迟到的消息不能再造单");
    }

    @Test
    @DisplayName("别人的排队单取消不掉：无 DB 行时靠 hold 里的客户 ID 判归属")
    void cannotCancelOthersQueuedOrder() {
        OrderPersistMessage message = preDeductOnly(910004L, SizeType.SMALL);

        BizException e = assertThrows(BizException.class,
                () -> asyncService.cancelQueued(message.orderNo(), 999999L));
        assertEquals(40400, e.getResultCode().getCode(), "越权取消要与\"单不存在\"同义，不给枚举线索");
        assertEquals(2, preDeduction.freeCount(cabinetId, SizeType.SMALL), "取消失败不该归还格口");
    }

    @Test
    @DisplayName("落库失败必须上抛：吞异常等于告诉 broker 成功、消息静默丢失")
    void persistFailurePropagatesForRetry() {
        OrderPersistMessage message = preDeductOnly(910006L, SizeType.SMALL);

        // 格口指向不存在的 ID：消费者必须抛，让 rocketmq-spring 回 RECONSUME_LATER
        OrderPersistMessage broken = new OrderPersistMessage(message.orderNo(), message.orderId(), TENANT,
                910006L, cabinetId, 999999L, SizeType.SMALL.name(), "V000001", 60, "{}", "req-broken", null);
        assertThrows(RuntimeException.class, () -> asyncService.persistFromMessage(broken),
                "落库失败必须上抛，吞掉等于告诉 broker 成功");

        Integer rows = jdbc.queryForObject("select count(*) from biz_storage_order where order_no = ?",
                Integer.class, message.orderNo());
        assertEquals(0, rows, "失败后不得留下半个订单行");

        String status = jdbc.queryForObject("select status from biz_msg_consume where msg_key = ?",
                String.class, message.orderNo());
        assertEquals("FAILED", status, "幂等记录要留下失败痕迹与重试次数，否则死信里无从查起");
    }
}
