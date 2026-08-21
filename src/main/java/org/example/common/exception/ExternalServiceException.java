package org.example.common.exception;

public class ExternalServiceException extends AppException {
    public ExternalServiceException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
