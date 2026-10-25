package com.wherelee.cabinet.common.api;

import java.util.List;

public record PageResult<T>(long pageNum,
                           long pageSize,
                           long total,
                           long pages,
                           List<T> records) {

    public static <T> PageResult<T> of(long pageNum, long pageSize, long total, long pages, List<T> records) {
        return new PageResult<>(pageNum, pageSize, total, pages, records == null ? List.of() : records);
    }

    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return new PageResult<>(pageNum, pageSize, 0L, 0L, List.of());
    }
}
