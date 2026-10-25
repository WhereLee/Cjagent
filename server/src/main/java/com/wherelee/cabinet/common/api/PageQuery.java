package com.wherelee.cabinet.common.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class PageQuery {

    public static final long DEFAULT_PAGE_SIZE = 20L;
    public static final long MAX_PAGE_SIZE = 200L;

    @Min(value = 1, message = "pageNum 最小为 1")
    private long pageNum = 1L;

    @Min(value = 1, message = "pageSize 最小为 1")
    @Max(value = MAX_PAGE_SIZE, message = "pageSize 最大为 200")
    private long pageSize = DEFAULT_PAGE_SIZE;

    private String orderBy;

    private boolean asc = false;

    public boolean hasSort() {
        return orderBy != null && !orderBy.isBlank();
    }

    public long getPageNum() {
        return pageNum;
    }

    public void setPageNum(long pageNum) {
        this.pageNum = pageNum;
    }

    public long getPageSize() {
        return pageSize;
    }

    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
    }

    public String getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(String orderBy) {
        this.orderBy = orderBy;
    }

    public boolean isAsc() {
        return asc;
    }

    public void setAsc(boolean asc) {
        this.asc = asc;
    }
}
