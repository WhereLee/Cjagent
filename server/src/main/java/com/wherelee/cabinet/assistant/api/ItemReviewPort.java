package com.wherelee.cabinet.assistant.api;

import com.wherelee.cabinet.locker.api.Presence;

import java.math.BigDecimal;

public interface ItemReviewPort {

    Review review(Request request);

    record Request(Long orderId, Long compartmentId, String slotNo,
                   String closedPhotoRef, String baselinePhotoRef) {
    }

    record Review(Presence presence, BigDecimal confidence, String note) {

        public boolean indeterminate() {
            return presence == null || presence == Presence.UNKNOWN;
        }

        public static Review unknown(String why) {
            return new Review(Presence.UNKNOWN, null, why);
        }
    }
}
