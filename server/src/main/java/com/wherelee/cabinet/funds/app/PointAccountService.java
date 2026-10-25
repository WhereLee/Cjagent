package com.wherelee.cabinet.funds.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.funds.domain.BizPointAccount;
import com.wherelee.cabinet.funds.domain.BizPointTxn;
import com.wherelee.cabinet.funds.api.PointTxnType;
import com.wherelee.cabinet.funds.infra.BizPointAccountMapper;
import com.wherelee.cabinet.funds.infra.BizPointTxnMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class PointAccountService {

    private static final Logger log = LoggerFactory.getLogger(PointAccountService.class);

    private final BizPointAccountMapper accountMapper;
    private final BizPointTxnMapper txnMapper;

    public PointAccountService(BizPointAccountMapper accountMapper, BizPointTxnMapper txnMapper) {
        this.accountMapper = accountMapper;
        this.txnMapper = txnMapper;
    }

    public record TxnResult(Long txnId, long balanceAfter, boolean duplicated) {
    }

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
            return accountMapper.selectOne(Wrappers.<BizPointAccount>lambdaQuery()
                    .eq(BizPointAccount::getCustomerId, customerId));
        }
    }

    public enum PostStatus { POSTED, INSUFFICIENT, DUPLICATE }

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
            return new PostAttempt(PostStatus.INSUFFICIENT, null, readBucket(account, type.bucket()));
        }

        BizPointAccount after = accountMapper.selectById(account.getId());
        long balanceAfter = readBucket(after, type.bucket());

        BizPointTxn txn = newTxn(account, customerId, type, signed, refType, refId, bizNo, remark, balanceAfter);
        try {
            txnMapper.insert(txn);
        } catch (DuplicateKeyException e) {
            log.info("并发重复入账，回滚本次变动 type={} bizNo={}", type, bizNo);
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, "该请求已在处理，请刷新后查看");
        }
        return new PostAttempt(PostStatus.POSTED, txn.getId(), balanceAfter);
    }

    public record PostAttempt(PostStatus status, Long txnId, Long balanceAfter) {
    }

    @Transactional
    public TxnResult post(Long customerId, PointTxnType type, long amount,
                          String refType, Long refId, String bizNo, String remark) {
        PostAttempt attempt = tryPost(customerId, type, amount, refType, refId, bizNo, remark);
        if (attempt.status() == PostStatus.INSUFFICIENT) {
            throw new BizException(ResultCode.POINT_INSUFFICIENT,
                    type.bucket() == PointTxnType.Bucket.FROZEN ? "冻结点数不足，请联系运营核对" : "点数不足，请先充值");
        }
        return new TxnResult(attempt.txnId(), attempt.balanceAfter() == null ? 0L : attempt.balanceAfter(),
                attempt.status() == PostStatus.DUPLICATE);
    }

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

    @Transactional
    public TxnResult recharge(Long customerId, long points, String channelTradeNo, String remark) {
        if (points <= 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "充值点数必须为正数");
        }
        return post(customerId, PointTxnType.RECHARGE, points, "RECHARGE", null,
                channelTradeNo, remark);
    }

    @Transactional
    public TxnResult freeze(Long customerId, long points, String refType, Long refId, String bizNo, String remark) {
        post(customerId, PointTxnType.FREEZE_OUT, points, refType, refId, bizNo, remark);
        return post(customerId, PointTxnType.FREEZE_IN, points, refType, refId, bizNo + ":in", remark);
    }

    @Transactional
    public TxnResult unfreeze(Long customerId, long points, String refType, Long refId, String bizNo, String remark) {
        post(customerId, PointTxnType.UNFREEZE_OUT, points, refType, refId, bizNo, remark);
        return post(customerId, PointTxnType.UNFREEZE_IN, points, refType, refId, bizNo + ":in", remark);
    }

    @Transactional
    public TxnResult consume(Long customerId, long points, String refType, Long refId, String bizNo, String remark) {
        return post(customerId, PointTxnType.CONSUME, points, refType, refId, bizNo, remark);
    }

    public BizPointAccount accountOf(Long customerId) {
        return accountMapper.selectOne(Wrappers.<BizPointAccount>lambdaQuery()
                .eq(BizPointAccount::getCustomerId, customerId));
    }

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
