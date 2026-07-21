package com.example.clawbot.exception;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 1. 捕获自定义的业务异常 (例如：密码错误、库存不足)
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        // 业务异常通常是预期内的，不需要打印长长的错误堆栈，只记录信息即可
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 2. 捕获参数校验异常 (例如：用户注册时没填邮箱)
     * 通常配合 @Valid 或 @Validated 使用
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidationException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("参数校验失败: {}", msg);
        return Result.error(400, msg);
    }

    /**
     * 3. 兜底处理：捕获所有未知的系统运行时异常 (例如：空指针、数据库断连)
     * 确保即使发生严重错误，用户看到的也是友好的提示，而不是一长串代码报错
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleSystemException(Exception e) {
        // 这种属于未知错误，需要打印完整的错误堆栈以便开发人员排查
        log.error("系统发生未知异常", e);
        return Result.error(500, "系统开小差了，请稍后再试～");
    }
}
