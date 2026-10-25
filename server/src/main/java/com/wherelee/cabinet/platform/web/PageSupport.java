package com.wherelee.cabinet.platform.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class PageSupport {

    private static final Pattern SAFE_PROPERTY = Pattern.compile("^[a-z][a-zA-Z0-9]{0,63}$");

    private PageSupport() {
    }

    public static <T> Page<T> toPage(PageQuery query, Set<String> sortable) {
        Page<T> page = new Page<>(query.getPageNum(), query.getPageSize());
        if (!query.hasSort()) {
            return page;
        }
        String property = query.getOrderBy().trim();
        if (!SAFE_PROPERTY.matcher(property).matches()
                || sortable == null
                || !sortable.contains(property)) {
            throw new BizException(ResultCode.PARAM_INVALID, "排序字段不允许");
        }
        String column = camelToUnderline(property);
        page.addOrder(query.isAsc() ? OrderItem.asc(column) : OrderItem.desc(column));
        return page;
    }

    public static <E, V> PageResult<V> toResult(IPage<E> page, Function<E, V> mapper) {
        List<V> records = page.getRecords().stream().map(mapper).toList();
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    static String camelToUnderline(String property) {
        StringBuilder sb = new StringBuilder(property.length() + 8);
        for (int i = 0; i < property.length(); i++) {
            char c = property.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
