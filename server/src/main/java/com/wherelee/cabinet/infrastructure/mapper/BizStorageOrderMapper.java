package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 寄存单 Mapper。
 *
 * <p>第 9 刀要往这里加"锁一条空闲格口"的自定义方法（悲观锁与 {@code SKIP LOCKED} 两种写法），
 * 现在不放：没有并发场景可验证的方法会写不出来也测不出来。
 */
@Mapper
public interface BizStorageOrderMapper extends BaseMapper<BizStorageOrder> {
}
