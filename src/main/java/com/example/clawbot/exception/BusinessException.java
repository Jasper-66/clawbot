package com.example.clawbot.exception;

// 业务异常类，携带错误码和中文消息，由全局异常处理器统一拦截
public class BusinessException extends RuntimeException {

    private Integer code;

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() { return code; }
}