package com.wherelee.cabinet.identity.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wherelee.cabinet.platform.web.PageSupport;
import com.wherelee.cabinet.identity.api.UserView;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.domain.entity.SysUser;
import com.wherelee.cabinet.infrastructure.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

@Service
public class UserQueryService implements com.wherelee.cabinet.identity.api.UserQueryPort {

    private static final Set<String> SORTABLE =
            Set.of("id", "username", "realName", "status", "createTime", "lastLoginAt");

    private final SysUserMapper userMapper;

    public UserQueryService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public PageResult<UserView> page(String keyword, Integer status, PageQuery pageQuery) {
        Page<SysUser> page = PageSupport.toPage(pageQuery, SORTABLE);
        if (!pageQuery.hasSort()) {
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
