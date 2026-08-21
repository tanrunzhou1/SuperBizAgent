package org.example.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.example.common.api.ApiError;
import org.example.common.api.ApiResponse;
import org.example.common.api.TraceIdContext;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(Exception exception, HttpServletRequest request) {
        String message = "请求参数不合法";
        if (exception instanceof MethodArgumentNotValidException validationException) {
            FieldError fieldError = validationException.getBindingResult().getFieldError();
            if (fieldError != null) {
                message = fieldError.getField() + ":" + fieldError.getDefaultMessage();
            }
        }
        return response(ErrorCode.INVALID_REQUEST, message, exception, request);
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(AppException exception, HttpServletRequest request) {
        return response(exception.getErrorCode(), exception.getMessage(), exception, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception, HttpServletRequest request) {
        return response(ErrorCode.SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR.getMessage(), exception, request);
    }

    private ResponseEntity<ApiResponse<Void>> response(ErrorCode errorCode, String message, Exception exception,
            HttpServletRequest request) {
        String traceId = TraceIdContext.getOrCreate();
        log.error("request failed, traceId={}, method={}, uri={}, errorCode={}", traceId,
                request.getMethod(), request.getRequestURI(), errorCode.getCode(), exception);
        ApiError error = ApiError.of(errorCode, message);
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiResponse.failure(error));
    }
}
