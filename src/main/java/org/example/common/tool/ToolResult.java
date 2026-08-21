package org.example.common.tool;

import lombok.Getter;
import lombok.Setter;
import org.example.common.api.ApiError;

@Getter
@Setter
public class ToolResult<T> {
    private boolean success;
    private int attempts;
    private T data;
    private String message;
    private ApiError error;

    public static <T> ToolResult<T> success(T data, String message, int attempts) {
        ToolResult<T> result = new ToolResult<>();
        result.setSuccess(true);
        result.setData(data);
        result.setMessage(message);
        result.setAttempts(attempts);
        return result;
    }

    public static <T> ToolResult<T> failure(ApiError error, int attempts) {
        ToolResult<T> result = new ToolResult<>();
        result.setSuccess(false);
        result.setAttempts(attempts);
        result.setMessage(error.getMessage());
        result.setError(error);
        return result;
    }
}
