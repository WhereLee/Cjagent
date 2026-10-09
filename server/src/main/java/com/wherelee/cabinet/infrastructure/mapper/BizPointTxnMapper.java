package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizPointTxn;
import org.apache.ibatis.annotations.Mapper;

/**
 * 点数流水：只 INSERT。
 *
 * <p><b>不提供任何 update/delete 语义</b>：流水一旦可改，"按流水重算余额"就失去意义，
 * 而那是本项目唯一能自证账平的机制。写错就再记一条 ADJUST 冲正，绝不回头改历史。
 *
 * <p>求和与幂等判定走 {@code BaseMapper} 的 wrapper（类型集合由 {@code PointTxnType} 推导，
 * 不在 SQL 里写死枚举串——那会造成"注释/SQL/代码"三处要同步的真相源）。
 */
@Mapper
public interface BizPointTxnMapper extends BaseMapper<BizPointTxn> {
}
