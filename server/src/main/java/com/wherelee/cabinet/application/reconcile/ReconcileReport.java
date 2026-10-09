package com.wherelee.cabinet.application.reconcile;

import java.util.List;

/**
 * 一次对账批次的结果（不可变，给日志、测试与后台页面同一份读法）。
 *
 * <p>为什么把差异做成"检查项 + 数量 + 样本"而不是直接返回一堆行：
 * 对账的消费者有三种——看板要的是数字，日志要的是前几条样本，运维要的是"这一项今天有没有"。
 * 一个结构三种用法都覆盖，才不需要每个调用方各自再拼一遍。
 *
 * @param tenantId       本次对账的租户
 * @param diffs          每个检查项的差异（<b>包括 0 的项</b>：0 也是结论，不能靠"没有这一项"表示通过）
 * @param accountsScanned 本次扫描过的账户行数（用于判断"对账跑没跑全"，与差异数一样重要）
 */
public record ReconcileReport(Long tenantId, List<Diff> diffs, long accountsScanned) {

    /**
     * @param check  检查项名（见 {@code ReconcileService} 的常量）
     * @param count  差异条数
     * @param samples 定位用的样本（最多几条，不是全量）
     */
    public record Diff(String check, long count, String samples) {

        public boolean clean() {
            return count == 0L;
        }
    }

    public long countOf(String check) {
        return diffs.stream().filter(d -> d.check().equals(check)).mapToLong(Diff::count).sum();
    }

    /** 任一检查项非 0 就不算通过：<b>滞留单也算差异</b>，因为它需要一个人工出口。 */
    public boolean clean() {
        return diffs.stream().allMatch(Diff::clean);
    }

    public List<String> failedChecks() {
        return diffs.stream().filter(d -> !d.clean()).map(Diff::check).toList();
    }
}
