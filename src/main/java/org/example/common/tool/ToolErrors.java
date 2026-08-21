package org.example.common.tool;

import org.example.common.api.ApiError;
import org.example.common.exception.ErrorCode;
import org.example.common.exception.AppException;

import java.io.InterruptedIOException;

public final class ToolErrors {
    private ToolErrors() {
    }

    public static ApiError from(ErrorCode defaultCode, Exception exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof AppException appException) {
                return ApiError.of(appException.getErrorCode(), appException.getMessage());
            }
            if (cause instanceof InterruptedIOException) {
                return ApiError.of(ErrorCode.TOOL_TIMEOUT);
            }
            cause = cause.getCause();
        }
        return ApiError.of(defaultCode);
    }
}
