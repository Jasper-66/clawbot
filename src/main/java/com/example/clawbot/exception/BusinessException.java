package com.example.clawbot.exception;

/** 业务异常类，携带错误码和可读消息，由 GlobalExceptionHandler 统一捕获处理。 */
public class BusinessException extends RuntimeException {

    private Integer code;

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() { return code; }
}