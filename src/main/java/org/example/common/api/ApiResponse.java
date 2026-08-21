package org.example.common.api;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(200);
        response.setMessage("success");
        response.setData(data);
        response.setTraceId(TraceIdContext.getOrCreate());
        return response;
    }

    public static <T> ApiResponse<T> failure(ApiError error) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(error.getCode());
        response.setMessage(error.getMessage());
        response.setTraceId(error.getTraceId());
        return response;
    }
}
