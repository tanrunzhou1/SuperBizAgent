package org.example.common.api;

import lombok.Getter;
import lombok.Setter;
import org.example.common.exception.ErrorCode;

@Getter
@Setter
public class ApiError {
    private int code;
    private String message;
    private String traceId;
    private boolean retryable;

    public static ApiError of(ErrorCode errorCode) {
        ApiError error = new ApiError();
        error.setCode(errorCode.getCode());
        error.setMessage(errorCode.getMessage());
        error.setRetryable(errorCode.isRetryable());
        error.setTraceId(TraceIdContext.getOrCreate());
        return error;
    }

    public static ApiError of(ErrorCode errorCode, String message) {
        ApiError error = of(errorCode);
        error.setMessage(message);
        return error;
    }
}
