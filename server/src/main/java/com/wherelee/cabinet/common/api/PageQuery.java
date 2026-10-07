package com.wherelee.cabinet.common.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 统一分页入参。<b>刻意不依赖 MyBatis-Plus</b>：分页是接口契约，用什么 ORM 是实现细节，
 * 这样 common 层可以被 ArchUnit 机检为"不依赖技术框架"。
 *
 * <p>三个约束各有理由：
 * <ul>
 *   <li>{@code pageSize} 上限 200：单页捞十万条是最常见的自伤式 DoS，
 *       而 MyBatis-Plus 的 {@code maxLimit=500} 只是最后一道保险；</li>
 *   <li>{@code orderBy} 传<b>实体属性名</b>而不是列名：不暴露真实 schema，
 *       也让白名单可以按属性校验（见 {@code PageSupport}）；</li>
 *   <li>默认不排序：排序字段必须由接口显式声明白名单，否则 {@code order by}
 *       就是一个注入口。</li>
 * </ul>
 */
public class PageQuery {

    public static final long DEFAULT_PAGE_SIZE = 20L;
    public static final long MAX_PAGE_SIZE = 200L;

    @Min(value = 1, message = "pageNum 最小为 1")
    private long pageNum = 1L;

    @Min(value = 1, message = "pageSize 最小为 1")
    @Max(value = MAX_PAGE_SIZE, message = "pageSize 最大为 200")
    private long pageSize = DEFAULT_PAGE_SIZE;

    /** 排序字段，写实体属性名（如 createTime），不是数据库列名。 */
    private String orderBy;

    /** 排序方向，默认倒序（列表页绝大多数是按时间倒着看）。 */
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
