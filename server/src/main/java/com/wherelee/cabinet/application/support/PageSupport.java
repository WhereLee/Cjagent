package com.wherelee.cabinet.application.support;

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

/**
 * {@link PageQuery} 与 MyBatis-Plus 分页对象之间的唯一转换点。
 *
 * <p><b>排序白名单不是可选项</b>：{@code order by} 拼的是字段名，不是绑定参数，
 * JDBC 的预编译帮不上忙。所以这里两道都要过：
 * <ol>
 *   <li>形状检查（必须是小写开头的驼峰标识符，挡掉 {@code 1;drop table} 这类）；</li>
 *   <li>白名单检查（每个接口只声明自己允许排序的属性）。</li>
 * </ol>
 * 只做第 1 道仍然不安全——合法字段名照样能拼出越权信息，例如按他表字段排序。
 *
 * <p>属性名会转成下划线列名再交给 SQL：业务代码传 camelCase，DB 列是 snake_case，
 * 转换只发生在这一处。
 */
public final class PageSupport {

    /** 合法属性名形状：小写字母开头，字母数字，最长 64。 */
    private static final Pattern SAFE_PROPERTY = Pattern.compile("^[a-z][a-zA-Z0-9]{0,63}$");

    private PageSupport() {
    }

    /**
     * @param sortable 允许排序的<b>实体属性名</b>集合；为空集合表示该接口不允许自定义排序
     * @throws BizException 40000 排序字段不合法或不在白名单内
     */
    public static <T> Page<T> toPage(PageQuery query, Set<String> sortable) {
        Page<T> page = new Page<>(query.getPageNum(), query.getPageSize());
        if (!query.hasSort()) {
            return page;
        }
        String property = query.getOrderBy().trim();
        if (!SAFE_PROPERTY.matcher(property).matches()
                || sortable == null
                || !sortable.contains(property)) {
            // 不把"哪些字段可排序"回显给调用方，否则等于送一份可猜字段的探测表
            throw new BizException(ResultCode.PARAM_INVALID, "排序字段不允许");
        }
        String column = camelToUnderline(property);
        page.addOrder(query.isAsc() ? OrderItem.asc(column) : OrderItem.desc(column));
        return page;
    }

    /** 查询结果转统一出参；{@code mapper} 只做 Entity→VO 映射，不在此处夹业务规则。 */
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
