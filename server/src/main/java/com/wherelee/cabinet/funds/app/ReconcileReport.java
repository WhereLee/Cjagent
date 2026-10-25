package com.wherelee.cabinet.funds.app;

import java.util.List;

public record ReconcileReport(Long tenantId, List<Diff> diffs, long accountsScanned) {

    public record Diff(String check, long count, String samples) {

        public boolean clean() {
            return count == 0L;
        }
    }

    public long countOf(String check) {
        return diffs.stream().filter(d -> d.check().equals(check)).mapToLong(Diff::count).sum();
    }

    public boolean clean() {
        return diffs.stream().allMatch(Diff::clean);
    }

    public List<String> failedChecks() {
        return diffs.stream().filter(d -> !d.clean()).map(Diff::check).toList();
    }
}
