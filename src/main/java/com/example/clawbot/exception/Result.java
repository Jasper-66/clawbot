package com.example.clawbot.exception;

import lombok.Data;

// 统一 API 响应包装类，包含状态码、消息和数据体
@Data
public class Result<T> {

    private Integer code;

    private String message;

    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.code = 200;
        result.message = "success";
        result.data = data;
        return result;
    }

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.code = code;
        result.message = message;
        return result;
    }

    public static <T> Result<T> error(String message) {
        return error(400, message);
    }
}
