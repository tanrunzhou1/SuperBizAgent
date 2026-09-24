package org.example.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    INVALID_REQUEST(400001, "请求参数不合法", HttpStatus.BAD_REQUEST, false),
    RESOURCE_NOT_FOUND(404001, "请求的资源不存在", HttpStatus.NOT_FOUND, false),
    BUSINESS_ERROR(422001, "业务处理失败", HttpStatus.UNPROCESSABLE_ENTITY, false),
    SESSION_TASK_IN_PROGRESS(409001, "当前会话正在执行 AIOps，请等待完成后再发送新消息", HttpStatus.CONFLICT, true),
    PROMETHEUS_UNAVAILABLE(503001, "监控平台暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE, true),
    MILVUS_UNAVAILABLE(503002, "知识库服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE, true),
    MCP_UNAVAILABLE(503003, "外部工具服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE, true),
    TOOL_TIMEOUT(504001, "工具调用超时，请稍后重试", HttpStatus.GATEWAY_TIMEOUT, true),
    MODEL_UNAVAILABLE(503004, "模型服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE, true),
    SYSTEM_ERROR(500001, "系统繁忙，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR, false);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
    private final boolean retryable;

    ErrorCode(int code, String message, HttpStatus httpStatus, boolean retryable) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }
}
