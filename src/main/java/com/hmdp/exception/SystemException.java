package com.hmdp.exception;

/**
 * 系统异常：表达「依赖出问题了」，<b>计入熔断</b>。
 *
 * <p>典型场景：Redis 超时/连接失败、DB 不可用、MQ 发送失败。与 {@link BusinessException} 的区别
 * 就是这个「计入 vs 不计入」——决定了熔断器会不会因为这类失败而打开。
 *
 * <p>与业务异常相反，这里<b>保留完整栈帧</b>：系统故障需要靠栈定位是哪个依赖、哪次调用出的问题。
 */
public class SystemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public SystemException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public SystemException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SystemException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getCode() {
        return errorCode.getCode();
    }
}
