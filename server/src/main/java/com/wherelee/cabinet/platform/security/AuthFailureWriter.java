package com.wherelee.cabinet.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.common.api.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class AuthFailureWriter {

    private AuthFailureWriter() {
    }

    public static void write(HttpServletResponse response,
                             ObjectMapper objectMapper,
                             int httpStatus,
                             ResultCode code,
                             String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(code, message)));
    }
}
