package com.hmdp.exception;

/**
 * 业务异常：表达「业务上没做成」，<b>不是系统故障</b>。
 *
 * <p>典型场景：库存不足、重复下单、参数不合法。这类异常在秒杀高峰期是<strong>最高频结果</strong>，
 * 必须配置进熔断器的 {@code ignoreExceptions}，否则故障率被业务结果污染，会把健康的依赖熔断掉。
 *
 * <p>性能：{@code writableStackTrace=false}。业务异常不需要栈帧，秒杀高频抛异常时
 * 省掉填充栈的开销（这也是 JDK 对控制流型异常的常见优化）。
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message, null, false, false);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause, false, false);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getCode() {
        return errorCode.getCode();
    }
}
