package com.wherelee.cabinet.locker.api;

/** 点位的只读快照。 */
public record SiteSnapshot(Long id, String siteCode, String name, String category, String address) {
}
