package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.storage.api.StorageOrderView;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.identity.api.CustomerQueryPort;
import com.wherelee.cabinet.identity.api.CustomerSnapshot;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 取件的第二条路径：**取件码 + 手机尾号**（定-25，用户 2026-10-12 定）。
 *
 * <p>为什么要两个而不是一个：取件码可以被拍照、转发、旁人瞥见，而"这个手机号后四位"
 * 只有物主本人容易知道；反过来手机尾号谁都能猜（四位只有 1 万个可能），码却是 9 千万个可能。
 * 两个各管一段，单独猜中任意一个都开不了柜。
 *
 * <p>三条口径都是沿用已有的，没有新造：
 * <ul>
 *   <li>错次与锁定用定-15 的机制（记在订单上、连续错 5 次锁该单开柜、阈值不变）；</li>
 *   <li>结算与"结束即开门"走 {@link StorageOrderService#pickup} 那条主路径，钱算法完全一致；</li>
 *   <li>失败原因不区分"码不存在"与"尾号不对"：这个端点匿名可达，能区分就等于送出一个
 *       可枚举的探测口（"哪些码是真实存在的"本身就是有价值的情报）。</li>
 * </ul>
 *
 * <p>码根本不存在时没地方计数（错次是记在订单上的），这一路靠端点的 IP 限流兜着；
 * 码真而尾号错，则照记不误——那正是有人拿着真码在试尾号。
 */
@Service
public class PublicPickupService {

    private static final Logger log = LoggerFactory.getLogger(PublicPickupService.class);

    /** 三种失败（码不存在、码对尾号错、尾号对码错）必须同一句话，见类注释。 */
    private static final String GENERIC_FAILURE = "取件码或手机尾号不正确，请核对后重试";

    private final BizStorageOrderMapper orderMapper;
    private final CustomerQueryPort customers;
    private final StorageOrderService orders;

    public PublicPickupService(BizStorageOrderMapper orderMapper, CustomerQueryPort customers,
                               StorageOrderService orders) {
        this.orderMapper = orderMapper;
        this.customers = customers;
        this.orders = orders;
    }

    public StorageOrderView pickup(String voucherCode, String phoneTail) {
        if (voucherCode == null || voucherCode.isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID, "请填写取件码");
        }
        if (phoneTail == null || !phoneTail.matches("\\d{4}")) {
            throw new BizException(ResultCode.PARAM_INVALID, "请填写手机号后四位");
        }
        String code = voucherCode.trim();

        // 这条查询故意跳租户（匿名请求没有租户上下文，带租户条件的 SQL 会被守卫拒掉）；
        // 码本身全表唯一，所以不会歧义。拿到 tenant_id 后下面立刻切回租户上下文
        BizStorageOrder hit = orderMapper.pickAliveByVoucher(code);
        if (hit == null) {
            log.warn("双因子取件未命中任何在途单（码错、或这单已经结束/取消）");
            throw new BizException(ResultCode.BIZ_ERROR, GENERIC_FAILURE);
        }

        return TenantContext.callAs(hit.getTenantId(), () -> {
            CustomerSnapshot customer = customers.byId(hit.getCustomerId()).orElse(null);
            if (!tailMatches(customer, phoneTail)) {
                // 码是真的、尾号不对：这就有人在拿真码试尾号，必须计入这张单的错次。
                // registerVoucherWrong 到阈值时自己抛锁定文案，没到阈值才继续往下报通用失败
                orders.registerVoucherWrong(hit);
                throw new BizException(ResultCode.BIZ_ERROR, GENERIC_FAILURE);
            }
            // 两个凭据都过了：把这张单交回主路径，由它做校验码归零、结算、开门取物——
            // 分叉出第二套结算流程迟早会出现"柜机取的价和手机取的价不一样"
            return orders.pickup(hit.getCustomerId(), hit.getOrderNo(), code);
        });
    }

    private boolean tailMatches(CustomerSnapshot customer, String phoneTail) {
        String phone = customer == null ? null : customer.phone();
        return phone != null && phone.length() >= 4
                && phone.substring(phone.length() - 4).equals(phoneTail);
    }
}
