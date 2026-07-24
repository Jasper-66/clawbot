package com.example.clawbot.exception;

import lombok.Data;

/** 统一响应包装类，包含状态码、消息和数据。 */
@Data
public class Result<T> {

    private Integer code;
    private String message;
    private T data;

    /** 静态工厂方法，快速创建错误响应。 */
    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.code = code;
        result.message = message;
        return result;
    }
}
