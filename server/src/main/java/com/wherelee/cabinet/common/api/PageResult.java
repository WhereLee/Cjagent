package com.wherelee.cabinet.common.api;

import java.util.List;

/**
 * 统一分页出参。
 *
 * <p>同时给 {@code total}（总条数）与 {@code pages}（总页数），并把请求参数原样回带：
 * 前端做"跳到最后一页""下一页禁用"时不需要自己再算一遍，也不会和服务端对 {@code pageSize}
 * 的理解不一致。<b>不返回 hasNext 之类派生字段</b>，那种字段一多就会互相矛盾。
 */
public record PageResult<T>(long pageNum,
                           long pageSize,
                           long total,
                           long pages,
                           List<T> records) {

    public static <T> PageResult<T> of(long pageNum, long pageSize, long total, long pages, List<T> records) {
        return new PageResult<>(pageNum, pageSize, total, pages, records == null ? List.of() : records);
    }

    /** 空页也要返回结构而不是 null，前端少一处判空分支。 */
    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return new PageResult<>(pageNum, pageSize, 0L, 0L, List.of());
    }
}
