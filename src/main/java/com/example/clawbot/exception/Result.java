package com.example.clawbot.exception;

import lombok.Data;

/**
 * 统一响应包装类。
 *
 * <p>所有 API 接口的返回数据都包装为此对象，包含三部分信息：
 * 状态码（{@code code}）、提示消息（{@code message}）和实际数据（{@code data}），
 * 使前端/客户端能够统一判断请求是否成功并获取信息。</p>
 *
 * <h3>典型响应示例</h3>
 * <pre>{@code
 * // 成功 — 由 Controller 按需构造（Spring 自动序列化为 JSON）
 * { "code": 200, "message": "success", "data": {...} }
 *
 * // 错误 — 由 GlobalExceptionHandler 通过 error() 工厂构造
 * { "code": 400, "message": "参数校验失败", "data": null }
 * }</pre>
 *
 * <p>使用 {@link #error(Integer, String)} 静态工厂方法快速创建错误响应，
 * 避免在各处重复 new + set 的样板代码。</p>
 *
 * <p>通过 Lombok {@link lombok.Data @Data} 注解自动生成：
 * getter/setter、{@code toString()}、{@code equals()}、{@code hashCode()}。</p>
 *
 * @param <T> 正常返回时 {@code data} 字段的实际类型；错误响应时此字段为 {@code null}
 * @see GlobalExceptionHandler
 */
@Data
public class Result<T> {

    /**
     * 业务状态码。
     *
     * <p>约定：200 表示成功，400 及以上表示业务或系统错误。
     * 由 {@link GlobalExceptionHandler} 自动赋值，Controller 无需手动设置。</p>
     */
    private Integer code;

    /**
     * 提示消息。
     *
     * <p>成功时可省略（默认为 "success"），错误时包含面向用户的友好说明。
     * 不应暴露内部异常堆栈或敏感信息。</p>
     */
    private String message;

    /**
     * 业务数据载荷。
     *
     * <p>成功时携带实际返回数据（可以是任意类型），错误时为 {@code null}。</p>
     */
    private T data;

    /**
     * 便捷创建错误响应的静态工厂方法。
     *
     * <p>适合在 {@link GlobalExceptionHandler} 或 Service 的 catch 块中使用，
     * 一行代码即可完成错误包装，无需手动 {@code new} → {@code setCode()} → {@code setMessage()}。</p>
     *
     * @param code    错误状态码，建议对齐 HTTP 语义（400 参数错误、502 上游异常等）
     * @param message 面向用户的错误描述，使用中文
     * @param <T>     数据泛型，错误响应中 {@code data} 固定为 {@code null}
     * @return 封装好的错误响应对象，{@code data} 字段为 {@code null}
     */
    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.code = code;
        result.message = message;
        return result;
    }
}
