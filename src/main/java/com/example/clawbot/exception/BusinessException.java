package com.example.clawbot.exception;

/**
 * 业务异常类。
 *
 * <p>用于表示预期内的、可向用户展示的错误（如参数校验失败、第三方 API 返回错误等），
 * 区别于系统异常（如 {@link NullPointerException}、数据库连接断开等不可预期错误）。</p>
 *
 * <h3>使用场景示例</h3>
 * <ul>
 *   <li>用户输入不合法：{@code throw new BusinessException(400, "城市名称不能为空")}</li>
 *   <li>第三方 API 返回错误：{@code throw new BusinessException(502, "天气服务暂时不可用")}</li>
 *   <li>权限不足：{@code throw new BusinessException(403, "无权限执行此操作")}</li>
 * </ul>
 *
 * <p>携带业务错误码和可读的错误描述消息，供 {@link GlobalExceptionHandler} 统一捕获
 * 并包装为 {@link Result} 结构返回给客户端。</p>
 *
 * <p>继承自 {@link RuntimeException}（非受检异常），无需在方法签名中显式声明，
 * 由 Spring AOP 代理和全局异常处理器透明拦截。</p>
 *
 * @see GlobalExceptionHandler#handleBusinessException(BusinessException)
 * @see Result#error(Integer, String)
 */
public class BusinessException extends RuntimeException {

    /**
     * 业务错误码。
     *
     * <p>通常与 HTTP 状态码语义对齐：
     * 400 — 参数错误、401 — 未授权、403 — 无权限、
     * 404 — 资源不存在、502 — 上游服务异常。</p>
     */
    private Integer code;

    /**
     * 构造业务异常。
     *
     * @param code    业务错误码，建议对齐 HTTP 状态码语义（如 400、403、502）
     * @param message 面向用户的错误描述信息，应使用中文且避免技术术语
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 获取业务错误码。
     *
     * @return 错误码，可能为 {@code null}
     */
    public Integer getCode() { return code; }
}