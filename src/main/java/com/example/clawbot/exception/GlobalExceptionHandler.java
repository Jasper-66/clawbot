package com.example.clawbot.exception;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理器 — Spring Boot 统一异常拦截中心。
 *
 * <p>通过 {@link RestControllerAdvice @RestControllerAdvice} 注册为全局 AOP 切面，
 * 拦截所有 Controller 层抛出的指定类型异常，统一包装为 {@link Result} 格式返回。</p>
 *
 * <h3>三层异常处理体系</h3>
 * <ol>
 *   <li><b>BusinessException</b> — 预期内的业务异常（参数错误、API 返回错误等），
 *       记录 warn 级别日志，携带具体错误码</li>
 *   <li><b>MethodArgumentNotValidException</b> — Spring 参数校验异常（{@code @Valid} 校验失败），
 *       统一返回 400 错误码</li>
 *   <li><b>Exception</b> — 所有未知系统异常（空指针、网络超时等），
 *       记录 error 级别日志（含完整堆栈），返回 500 通用提示</li>
 * </ol>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>业务异常（预期内）→ 不安慰用户，直接显示具体错误原因</li>
 *   <li>系统异常（非预期）→ 隐藏技术细节，统一友好提示"系统开小差了"</li>
 *   <li>所有异常均不向上层 HTTP 容器抛出堆栈，避免信息泄露</li>
 * </ul>
 *
 * @see BusinessException
 * @see Result#error(Integer, String)
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 1. 捕获自定义的业务异常（如参数缺失、余额不足、API 调用失败等）。
     *
     * <p>业务异常是预期内的错误，通常由 Service 或 Tool 在检测到不满足前置条件时主动抛出。
     * 错误码携带具体的业务含义（400 参数错误、502 上游异常等），
     * 日志只需记录消息文本即可，不需要堆栈信息。</p>
     *
     * @param e 业务异常实例，包含错误码和错误描述
     * @return 统一错误响应（code = e.getCode(), message = e.getMessage()）
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 2. 捕获参数校验异常（例如：用户未填写必填字段、邮箱格式错误等）。
     *
     * <p>当 Controller 参数使用 {@code @Valid} 或 {@code @Validated} 注解时，
     * Spring 会自动执行 bean validation。校验失败后会抛出
     * {@link MethodArgumentNotValidException}，其中包含所有校验失败的详细信息。</p>
     *
     * <p>这里只取第一个错误消息（{@code getAllErrors().get(0).getDefaultMessage()}），
     * 避免一次性输出所有字段的校验失败信息造成消息过长。统一返回 HTTP 400 语义。</p>
     *
     * @param e 参数校验异常，包含 bindingResult 中所有字段的错误信息
     * @return 统一错误响应（code = 400, message = 第一个校验失败消息）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidationException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("参数校验失败: {}", msg);
        return Result.error(400, msg);
    }

    /**
     * 3. 兜底处理：捕获所有未知的系统运行时异常（空指针、数组越界、网络断连等）。
     *
     * <p>这是异常处理体系的最后防线，确保即使发生严重未预期错误，用户仍然收到
     * 友好的中文提示，而不是一长串堆栈或 HTTP 500 默认页面。</p>
     *
     * <p>日志级别为 error，且需要打印完整堆栈（{@code log.error("消息", e)}），
     * 因为此类异常说明系统存在 bug 或外部依赖异常，需要开发人员介入排查。</p>
     *
     * @param e 未被前两个处理器覆盖的任何 Throwable
     * @return 统一错误响应（code = 500, message = "系统开小差了，请稍后再试～"）
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleSystemException(Exception e) {
        log.error("系统发生未知异常", e);
        return Result.error(500, "系统开小差了，请稍后再试～");
    }
}
