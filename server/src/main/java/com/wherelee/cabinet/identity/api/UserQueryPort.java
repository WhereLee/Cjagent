package com.wherelee.cabinet.identity.api;

import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;

/**
 * 后台账号查询入口。
 *
 * <p>账号列表把租户过滤、逻辑删除、状态筛选都关在身份服务里；接口层只传关键字与分页，
 * 不会因为“再加一个筛选条件”而直接改到用户表。
 */
public interface UserQueryPort {

    PageResult<UserView> page(String keyword, Integer status, PageQuery pageQuery);
}
