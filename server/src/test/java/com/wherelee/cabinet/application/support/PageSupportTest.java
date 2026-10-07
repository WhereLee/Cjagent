package com.wherelee.cabinet.application.support;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageSupportTest {

    private static final Set<String> SORTABLE = Set.of("createTime", "username", "status");

    private static PageQuery query(String orderBy, boolean asc) {
        PageQuery query = new PageQuery();
        query.setPageNum(2);
        query.setPageSize(50);
        query.setOrderBy(orderBy);
        query.setAsc(asc);
        return query;
    }

    @Test
    @DisplayName("分页参数原样透传，未写排序时不带 order")
    void passesThroughPaging() {
        PageQuery q = new PageQuery();

        Page<Object> page = PageSupport.toPage(q, SORTABLE);

        assertEquals(1L, page.getCurrent());
        assertEquals(20L, page.getSize());
        assertTrue(page.orders().isEmpty(), "默认不该有排序");
    }

    @Test
    @DisplayName("白名单内字段转成下划线列名，方向正确")
    void appliesWhitelistedSort() {
        Page<Object> desc = PageSupport.toPage(query("createTime", false), SORTABLE);
        assertEquals(List.of("create_time"), desc.orders().stream().map(o -> o.getColumn()).toList());
        assertFalse(desc.orders().get(0).isAsc(), "asc=false 应为倒序");

        Page<Object> asc = PageSupport.toPage(query("username", true), SORTABLE);
        assertEquals(List.of("username"), asc.orders().stream().map(o -> o.getColumn()).toList());
        assertTrue(asc.orders().get(0).isAsc());
    }

    @Test
    @DisplayName("白名单外字段被拒，且不回显可排序字段清单")
    void rejectsFieldOutsideWhitelist() {
        BizException e = assertThrows(BizException.class,
                () -> PageSupport.toPage(query("passwordHash", false), SORTABLE));
        assertEquals(40000, e.getResultCode().getCode());
        assertTrue(!e.getMessage().contains("createTime"), "报错不该泄露可用排序字段: " + e.getMessage());
    }

    @Test
    @DisplayName("注入形状直接被拒（order by 拼的是标识符，不是绑定参数）")
    void rejectsInjectionShapes() {
        for (String evil : List.of("1; drop table sys_user", "username asc, (select 1)", "USERNAME",
                "user_name", "a".repeat(65))) {
            assertThrows(BizException.class, () -> PageSupport.toPage(query(evil, false), Set.of(evil)),
                    "形状不合法必须被拦下: " + evil);
        }
    }

    @Test
    @DisplayName("即使字段名合法，接口不开放排序时一律拒绝")
    void emptyWhitelistMeansNoSorting() {
        assertThrows(BizException.class,
                () -> PageSupport.toPage(query("createTime", false), Set.of()));
    }

    @Test
    @DisplayName("驼峰转下划线只在边界发生一次")
    void convertsCamelToUnderscore() {
        assertEquals("create_time", PageSupport.camelToUnderline("createTime"));
        assertEquals("id", PageSupport.camelToUnderline("id"));
        assertEquals("contact_phone1", PageSupport.camelToUnderline("contactPhone1"));
    }
}
