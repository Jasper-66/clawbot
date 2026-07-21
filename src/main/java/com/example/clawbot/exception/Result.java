package com.example.clawbot.exception;


import lombok.Data;

@Data
public class Result<T> {
    private Integer code;    // 状态码，例如：200成功，500系统异常，400业务错误
    private String message;  // 友好的错误提示信息
    private T data;          // 正常返回时的数据

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.code = code;
        result.message = message;
        return result;
    }
}
