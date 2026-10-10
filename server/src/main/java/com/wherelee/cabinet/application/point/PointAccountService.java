package com.wherelee.cabinet.application.point;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizPointAccount;
import com.wherelee.cabinet.domain.entity.BizPointTxn;
import com.wherelee.cabinet.domain.enums.PointTxnType;
import com.wherelee.cabinet.infrastructure.mapper.BizPointAccountMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizPointTxnMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 点数账本：所有余额变动的唯一入口。
 *
 * <p>三条规则撑起重构后的全部正确性：
 * <ol>
 *   <li><b>符号由类型定</b>（{@link PointTxnType#normalize}）：调用方传错正负不会把账搞反；</li>
 *   <li><b>幂等下沉到索引</b>：{@code uk_txn_biz(biz_type, biz_no)} 才是一次业务事件的最终裁判，
 *       本类的"先查一次"只是省掉无谓的行锁；</li>
 *   <li><b>余额靠条件更新，不靠先查后改</b>：影响 0 行就是余额不足。</li>
 * </ol>
 *
 * <p>账户更新与流水插入<b>必须在同一事务</b>：否则会出现"余额变了没流水"或"有流水没改余额"，
 * 而 {@link #verifyLedger} 这个自证手段就废了。这也是本方法标 {@code @Transactional} 的原因——
 * 它不涉及任何外部 I/O，与第 11 刀"等回执不得持有事务"并不矛盾。
 */
@Service
public class PointAccountService {

    private static final Logger log = LoggerFactory.getLogger(PointAccountService.class);

    private final BizPointAccountMapper accountMapper;
    private final BizPointTxnMapper txnMapper;

    public PointAccountService(BizPointAccountMapper accountMapper, BizPointTxnMapper txnMapper) {
        this.accountMapper = accountMapper;
        this.txnMapper = txnMapper;
    }

    /**
     * @param duplicated   是否命中幂等（同一业务事件重复到达）
     * @param balanceAfter 该栏变动后的余额快照
     */
    public record TxnResult(Long txnId, long balanceAfter, boolean duplicated) {
    }

    /** 取账户，没有就建。并发建号靠 {@code uk_point_account_customer} 兜住。 */
    public BizPointAccount ensureAccount(Long customerId) {
        BizPointAccount account = accountMapper.selectOne(Wrappers.<BizPointAccount>lambdaQuery()
                .eq(BizPointAccount::getCustomerId, customerId));
        if (account != null) {
            return account;
        }
        BizPointAccount fresh = new BizPointAccount();
        fresh.setCustomerId(customerId);
        fresh.setPoints(0L);
        fresh.setFrozenPoints(0L);
        fresh.setVersion(0);
        try {
            accountMapper.insert(fresh);
            return fresh;
        } catch (DuplicateKeyException e) {
            // 并发里别人刚建好：重读一次，不能把"已存在"当失败
            return accountMapper.selectOne(Wrappers.<BizPointAccount>lambdaQuery()
                    .eq(BizPointAccount::getCustomerId, customerId));
        }
    }

    /**
     * 不抛异常的版本：返回结果而不是靠异常传达“余额不足”。
     *
     * <p><b>为什么必须有这个入口</b>：“余额不足”在结算里是一个**业务分支**（要记欠费继续收尾），
     * 不是失败。而嵌套调用同一个事务的 {@code @Transactional} 方法招出异常时，
     * Spring 会把整个物理事务标成 rollback-only——外层就算接住了异常，提交时仍会抱
     * {@code UnexpectedRollbackException}。“用异常做分支”在这种地方不是风格问题，是 bug。
     */
    public enum PostStatus { POSTED, INSUFFICIENT, DUPLICATE }

    /** 尝试记账：不抛余额异常。返回状态与变动后余额。 */
    @Transactional
    public PostAttempt tryPost(Long customerId, PointTxnType type, long amount,
                               String refType, Long refId, String bizNo, String remark) {
        long signed = type.normalize(amount);
        if (signed == 0 && type != PointTxnType.ADJUST) {
            throw new BizException(ResultCode.PARAM_INVALID, "点数变动不能为 0：" + type);
        }

        BizPointTxn existing = findTxn(type, bizNo);
        if (existing != null) {
            log.info("点数流水幂等命中 type={} bizNo={} 已存在 id={}", type, bizNo, existing.getId());
            return new PostAttempt(PostStatus.DUPLICATE, existing.getId(),
                    existing.getBalanceAfter() == null ? 0L : existing.getBalanceAfter());
        }

        BizPointAccount account = ensureAccount(customerId);
        int hit = applyToBucket(account, type, signed);
        if (hit == 0) {
            // 不抛：让调用方自己决定“失败”还是“记欠费继续”
            return new PostAttempt(PostStatus.INSUFFICIENT, null, readBucket(account, type.bucket()));
        }

        BizPointAccount after = accountMapper.selectById(account.getId());
        long balanceAfter = readBucket(after, type.bucket());

        BizPointTxn txn = newTxn(account, customerId, type, signed, refType, refId, bizNo, remark, balanceAfter);
        try {
            txnMapper.insert(txn);
        } catch (DuplicateKeyException e) {
            // 并发同 bizNo：必须让事务回滚撤销刚才的余额变动，绝不能“钱动了却没流水”
            log.info("并发重复入账，回滚本次变动 type={} bizNo={}", type, bizNo);
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, "该请求已在处理，请刷新后查看");
        }
        return new PostAttempt(PostStatus.POSTED, txn.getId(), balanceAfter);
    }

    public record PostAttempt(PostStatus status, Long txnId, Long balanceAfter) {
    }

    /**
     * 记一笔流水并同步余额（余额不足则抛 10412、整个用例回滚）。
     *
     * @param amount 变动点数（正数即可，符号由类型决定；ADJUST 允许带符号）
     * @param bizNo  业务幂等号，如 {@code 单号:deposit}、充值通道流水号
     */
    @Transactional
    public TxnResult post(Long customerId, PointTxnType type, long amount,
                          String refType, Long refId, String bizNo, String remark) {
        PostAttempt attempt = tryPost(customerId, type, amount, refType, refId, bizNo, remark);
        if (attempt.status() == PostStatus.INSUFFICIENT) {
            // 余额不足是业务结果，不是系统故障：抛到这里事务回滚，流水不会留下半条
            throw new BizException(ResultCode.POINT_INSUFFICIENT,
                    type.bucket() == PointTxnType.Bucket.FROZEN ? "冻结点数不足，请联系运营核对" : "点数不足，请先充值");
        }
        return new TxnResult(attempt.txnId(), attempt.balanceAfter() == null ? 0L : attempt.balanceAfter(),
                attempt.status() == PostStatus.DUPLICATE);
    }

    /**
     * 按类型影响的那一栏做条件变更。“一笔只动一栏”是记账能自证的前提，
     * 栏位选错就会把押金算进可花余额（或反之）。
     */
    private int applyToBucket(BizPointAccount account, PointTxnType type, long signed) {
        return switch (type.bucket()) {
            case POINTS -> accountMapper.changeAvailable(account.getId(), signed);
            case FROZEN -> accountMapper.changeFrozen(account.getId(), signed);
            case DEPOSIT -> accountMapper.changeDeposit(account.getId(), signed);
        };
    }

    private long readBucket(BizPointAccount account, PointTxnType.Bucket bucket) {
        Long value = switch (bucket) {
            case POINTS -> account.getPoints();
            case FROZEN -> account.getFrozenPoints();
            case DEPOSIT -> account.getDepositPoints();
        };
        return value == null ? 0L : value;
    }

    /**
     * 把账户押金划足到 target。<b>只补差额，交过就不重复划</b>。
     *
     * <p>两笔（可用 -X、押金 +X）必须同事务成对：只成功一笔就是“钱凭空消失”
     * 或“没扣钱却显示已交押金”。余额不足让下单那一步整体失败，
     * 而不是先占住格口再告诉用户押金不够。
     */
    @Transactional
    public void holdAccountDeposit(Long customerId, long target, String bizNo, String remark) {
        BizPointAccount account = ensureAccount(customerId);
        long need = target - readBucket(account, PointTxnType.Bucket.DEPOSIT);
        if (need <= 0) {
            return;
        }
        post(customerId, PointTxnType.DEPOSIT_OUT, need, "DEPOSIT_ACCOUNT", customerId, bizNo, remark);
        post(customerId, PointTxnType.DEPOSIT_IN, need, "DEPOSIT_ACCOUNT", customerId, bizNo + ":in", remark);
        log.info("账户押金划足 customer={} 本次划转={} 目标={}", customerId, need, target);
    }

    /** 当前已押金额（给“能不能下单”的前置判据与退押金文案用）。 */
    public long depositOf(Long customerId) {
        return readBucket(ensureAccount(customerId), PointTxnType.Bucket.DEPOSIT);
    }

    /**
     * 退押金：整栅押金回到可用。
     *
     * <p>调用方负责先抵欠款（那个金额走 CONSUME，不走这两笔）——因为
     * “抵欠”是消耗、“退回”是栏位转换，混在一笔里两栏就对不上了。
     *
     * @return 实际退回的押金点数
     */
    @Transactional
    public long releaseAccountDeposit(Long customerId, String bizNo, String remark) {
        long held = depositOf(customerId);
        if (held <= 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "当前没有已押的押金，无需退回");
        }
        post(customerId, PointTxnType.DEPOSIT_BACK_OUT, held, "DEPOSIT_ACCOUNT", customerId, bizNo, remark);
        post(customerId, PointTxnType.DEPOSIT_BACK_IN, held, "DEPOSIT_ACCOUNT", customerId, bizNo + ":in", remark);
        log.info("账户押金退回 customer={} 金额={}", customerId, held);
        return held;
    }

    private BizPointTxn newTxn(BizPointAccount account, Long customerId, PointTxnType type, long signed,
                               String refType, Long refId, String bizNo, String remark, long balanceAfter) {
        BizPointTxn txn = new BizPointTxn();
        txn.setTenantId(account.getTenantId() != null ? account.getTenantId() : TenantContext.current());
        txn.setCustomerId(customerId);
        txn.setBizType(type);
        txn.setAmount(signed);
        txn.setBalanceAfter(balanceAfter);
        txn.setRefType(refType);
        txn.setRefId(refId);
        txn.setBizNo(bizNo);
        txn.setRemark(remark);
        txn.setCreateTime(LocalDateTime.now());
        return txn;
    }

    /** 充值（当前由内部接口/Mock 通道调用；真实支付通道在第 13 刀接）。 */
    @Transactional
    public TxnResult recharge(Long customerId, long points, String channelTradeNo, String remark) {
        if (points <= 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "充值点数必须为正数");
        }
        return post(customerId, PointTxnType.RECHARGE, points, "RECHARGE", null,
                channelTradeNo, remark);
    }

    /** 冻结一笔（押金或预估费用）。可用不足会抛 POINT_INSUFFICIENT。 */
    @Transactional
    public TxnResult freeze(Long customerId, long points, String refType, Long refId, String bizNo, String remark) {
        post(customerId, PointTxnType.FREEZE_OUT, points, refType, refId, bizNo, remark);
        return post(customerId, PointTxnType.FREEZE_IN, points, refType, refId, bizNo + ":in", remark);
    }

    /** 解冻一笔：先减冻结再回可用，两笔成对。 */
    @Transactional
    public TxnResult unfreeze(Long customerId, long points, String refType, Long refId, String bizNo, String remark) {
        post(customerId, PointTxnType.UNFREEZE_OUT, points, refType, refId, bizNo, remark);
        return post(customerId, PointTxnType.UNFREEZE_IN, points, refType, refId, bizNo + ":in", remark);
    }

    /** 真实消耗（结算/补扣）。 */
    @Transactional
    public TxnResult consume(Long customerId, long points, String refType, Long refId, String bizNo, String remark) {
        return post(customerId, PointTxnType.CONSUME, points, refType, refId, bizNo, remark);
    }

    public BizPointAccount accountOf(Long customerId) {
        return accountMapper.selectOne(Wrappers.<BizPointAccount>lambdaQuery()
                .eq(BizPointAccount::getCustomerId, customerId));
    }

    /**
     * 不变量 3 的实现：<b>两栏余额必须分别等于对应类型流水之和</b>。
     *
     * <p>类型集合由枚举推导（{@code bucket}），不在 SQL 里写死枚举串：
     * 否则新增一个类型时会忘记归类，账平校验会静默漏算那一笔——比不过校验更危险。
     *
     * @return 校验通过返回空串，否则返回可读的差异描述（便于测试与运维定位）
     */
    public String verifyLedger(Long customerId) {
        BizPointAccount account = accountOf(customerId);
        long points = account == null ? 0L : account.getPoints();
        long frozen = account == null ? 0L : account.getFrozenPoints();
        long sumPoints = sumOfBucket(customerId, PointTxnType.Bucket.POINTS);
        long sumFrozen = sumOfBucket(customerId, PointTxnType.Bucket.FROZEN);
        if (points != sumPoints) {
            return "可用点数不平：账户=" + points + " Σ流水=" + sumPoints;
        }
        if (frozen != sumFrozen) {
            return "冻结点数不平：账户=" + frozen + " Σ流水=" + sumFrozen;
        }
        return "";
    }

    /** 从流水重算两栏余额（对账作业用；本刀只提供能力，调度在第 13 刀接）。 */
    public long[] recalculate(Long customerId) {
        return new long[]{sumOfBucket(customerId, PointTxnType.Bucket.POINTS),
                sumOfBucket(customerId, PointTxnType.Bucket.FROZEN)};
    }

    private long sumOfBucket(Long customerId, PointTxnType.Bucket bucket) {
        List<String> types = Arrays.stream(PointTxnType.values())
                .filter(t -> t.bucket() == bucket)
                .map(Enum::name)
                .toList();
        Map<String, Object> row = txnMapper.selectMaps(Wrappers.<BizPointTxn>query()
                .select("coalesce(sum(amount), 0) as total")
                .eq("customer_id", customerId)
                .in("biz_type", types)).stream().findFirst().orElse(null);
        return row == null ? 0L : ((Number) row.get("total")).longValue();
    }

    private BizPointTxn findTxn(PointTxnType type, String bizNo) {
        return txnMapper.selectOne(Wrappers.<BizPointTxn>lambdaQuery()
                .eq(BizPointTxn::getBizType, type)
                .eq(BizPointTxn::getBizNo, bizNo)
                .last("limit 1"));
    }
}
