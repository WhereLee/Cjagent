package com.wherelee.cabinet.common.mask;

public enum MaskType {

    PHONE {
        @Override
        public String mask(String raw) {
            return keepHeadTail(raw, 3, 4);
        }
    },

    ID_CARD {
        @Override
        public String mask(String raw) {
            return keepHeadTail(raw, 6, 4);
        }
    },

    EMAIL {
        @Override
        public String mask(String raw) {
            int at = raw.indexOf('@');
            if (at <= 0) {
                return repeat(raw.length());
            }
            return raw.charAt(0) + repeat(at - 1) + raw.substring(at);
        }
    },

    NAME {
        @Override
        public String mask(String raw) {
            return raw.isEmpty() ? raw : raw.charAt(0) + repeat(raw.length() - 1);
        }
    },

    ADDRESS {
        @Override
        public String mask(String raw) {
            return keepHeadTail(raw, 6, 0);
        }
    },

    BANK_CARD {
        @Override
        public String mask(String raw) {
            return keepHeadTail(raw, 0, 4);
        }
    },

    ALL {
        @Override
        public String mask(String raw) {
            return repeat(raw.length());
        }
    };

    protected static final char FILL = '*';

    public abstract String mask(String raw);

    protected static String keepHeadTail(String raw, int head, int tail) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        if (raw.length() <= head + tail) {
            return repeat(raw.length());
        }
        return raw.substring(0, head) + repeat(raw.length() - head - tail)
                + (tail > 0 ? raw.substring(raw.length() - tail) : "");
    }

    protected static String repeat(int count) {
        return count <= 0 ? "" : String.valueOf(FILL).repeat(count);
    }
}
