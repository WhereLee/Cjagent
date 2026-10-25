package com.wherelee.cabinet.identity.api;

import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;

public interface UserQueryPort {

    PageResult<UserView> page(String keyword, Integer status, PageQuery pageQuery);
}
