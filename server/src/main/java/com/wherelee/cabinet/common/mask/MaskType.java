package com.wherelee.cabinet.common.mask;

/**
 * 脱敏样式与对应保留策略。
 *
 * <p>换电柜项目里手机号、身份证、位置是<b>常规字段</b>（骑手联系、实名、站点坐标），
 * 所以脱敏必须在底座阶段定下来：等接口都发出去了再改返回结构，前后端都要返工。
 */
public enum MaskType {

    /** 138****0000：保留前 3 后 4。 */
    PHONE {
        @Override
        public String mask(String raw) {
            return keepHeadTail(raw, 3, 4);
        }
    },

    /** 110101********1234：保留前 6 后 4（后 4 位含校验位，够客服核对）。 */
    ID_CARD {
        @Override
        public String mask(String raw) {
            return keepHeadTail(raw, 6, 4);
        }
    },

    /** a***@example.com：只保留首字符与域名。 */
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

    /** 姓名只保留姓。 */
    NAME {
        @Override
        public String mask(String raw) {
            return raw.isEmpty() ? raw : raw.charAt(0) + repeat(raw.length() - 1);
        }
    },

    /** 地址只保留到区级（后面全部遮掉）。 */
    ADDRESS {
        @Override
        public String mask(String raw) {
            return keepHeadTail(raw, 6, 0);
        }
    },

    /** 银行卡等长编号：只留后 4 位。 */
    BANK_CARD {
        @Override
        public String mask(String raw) {
            return keepHeadTail(raw, 0, 4);
        }
    },

    /** 全部遮掉（口令、token 这类绝不出网的字段）。 */
    ALL {
        @Override
        public String mask(String raw) {
            return repeat(raw.length());
        }
    };

    protected static final char FILL = '*';

    /**
     * 按类型遮蔽。
     *
     * <p>约定：<b>长度不足时全遮而不是原样返回</b>。否则"手机号只有 5 位"这类脏数据
     * 会整串漏出去，而这类数据在真实库里并不罕见。
     */
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
