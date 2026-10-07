package com.wherelee.cabinet.common.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RTest {

    @Test
    @DisplayName("错误码不允许重复，否则前端无法按 code 分支")
    void resultCodeCodesAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (ResultCode code : ResultCode.values()) {
            assertTrue(seen.add(code.getCode()), "重复的错误码: " + code.name());
            assertFalse(code.getMessage().isBlank(), "错误码缺描述: " + code.name());
        }
    }

    @Test
    @DisplayName("错误码落在约定分段内：0 成功 / 1xxxx 业务 / 4xxxx 客户端 / 5xxxx 服务端")
    void resultCodeRangesFollowConvention() {
        for (ResultCode code : ResultCode.values()) {
            int c = code.getCode();
            boolean inRange = c == 0
                    || (c >= 10000 && c < 20000)
                    || (c >= 40000 && c < 50000)
                    || (c >= 50000 && c < 60000);
            assertTrue(inRange, "错误码不在约定分段: " + code.name() + "=" + c);
        }
    }

    @Test
    @DisplayName("成功响应带数据和时间戳")
    void okCarriesDataAndTimestamp() {
        R<String> r = R.ok("abc");
        assertEquals(ResultCode.SUCCESS.getCode(), r.getCode());
        assertEquals("abc", r.getData());
        assertNotNull(r.getTimestamp());
    }

    @Test
    @DisplayName("失败响应可覆盖默认描述，且不带 data")
    void failAllowsMessageOverride() {
        R<Void> r = R.fail(ResultCode.BIZ_ERROR, "余额不足");
        assertEquals(ResultCode.BIZ_ERROR.getCode(), r.getCode());
        assertEquals("余额不足", r.getMessage());
        assertNull(r.getData());
    }
}
