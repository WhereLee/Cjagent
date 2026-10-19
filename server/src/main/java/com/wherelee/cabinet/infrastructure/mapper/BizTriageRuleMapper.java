package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizTriageRule;
import org.apache.ibatis.annotations.Mapper;

/** 分诊规则读写。 */
@Mapper
public interface BizTriageRuleMapper extends BaseMapper<BizTriageRule> {
}
