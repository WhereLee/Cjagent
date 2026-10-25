package com.wherelee.cabinet.common.exception;

import com.wherelee.cabinet.common.api.ResultCode;

public class BizException extends RuntimeException {

    private final ResultCode resultCode;

    public BizException(String message) {
        this(ResultCode.BIZ_ERROR, message);
    }

    public BizException(ResultCode resultCode) {
        this(resultCode, resultCode.getMessage());
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}
