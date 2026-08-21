package org.example.common.exception;

public class BusinessException extends AppException {
    public BusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
