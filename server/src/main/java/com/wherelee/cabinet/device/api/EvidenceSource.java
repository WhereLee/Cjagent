package com.wherelee.cabinet.device.api;

import java.util.Locale;

public enum EvidenceSource {

    INFRARED,
    DEVICE_PROBE,
    AI;

    public static EvidenceSource of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
