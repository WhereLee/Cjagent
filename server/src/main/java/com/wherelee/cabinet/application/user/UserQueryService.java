package com.wherelee.cabinet.application.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wherelee.cabinet.application.support.PageSupport;
import com.wherelee.cabinet.application.user.dto.UserView;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.domain.entity.SysUser;
import com.wherelee.cabinet.infrastructure.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 后台账号查询（只读）。租户隔离不需要在这里写任何条件：
 * {@code sys_user} 不在白名单，SQL 会被自动加上 {@code tenant_id = 当前租户}。
 */
@Service
public class UserQueryService {

    /**
     * 本接口允许排序的属性白名单。加字段前先想清楚：
     * 能被排序的字段，攻击者就能通过二分/时序观察其值分布，所以只放展示型与时间型字段。
     */
    private static final Set<String> SORTABLE =
            Set.of("id", "username", "realName", "status", "createTime", "lastLoginAt");

    private final SysUserMapper userMapper;

    public UserQueryService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public PageResult<UserView> page(String keyword, Integer status, PageQuery pageQuery) {
        Page<SysUser> page = PageSupport.toPage(pageQuery, SORTABLE);
        if (!pageQuery.hasSort()) {
            // 无排序时 MySQL 不保证翻页稳定，同一条记录可能被跳过或重复出现，
            // 所以必须有一个兜底排序；用 id 倒序（新记录优先，且值唯一、天然稳定）
            page.addOrder(OrderItem.desc("id"));
        }

        LambdaQueryWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaQuery()
                .eq(status != null, SysUser::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(SysUser::getUsername, keyword)
                        .or()
                        .like(SysUser::getRealName, keyword));

        IPage<SysUser> result = userMapper.selectPage(page, wrapper);
        return PageSupport.toResult(result, UserQueryService::toView);
    }

    private static UserView toView(SysUser user) {
        return new UserView(user.getId(), user.getUsername(), user.getRealName(), user.getPhone(),
                user.getStatus(), user.getLastLoginAt(), user.getCreateTime());
    }
}
