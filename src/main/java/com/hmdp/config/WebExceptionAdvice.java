package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.exception.BusinessException;
import com.hmdp.exception.ErrorCode;
import com.hmdp.exception.SystemException;
import com.hmdp.observability.TraceContext;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 全局异常处理器。
 *
 * <p><b>分层原则</b>：异常的类型决定了它「是不是系统故障」，进而决定响应状态码
 * 和是否计入熔断。四类出口：
 * <pre>
 *   BusinessException          → 200 + code      业务结果，不熔断
 *   SystemException            → 503 + traceId   依赖故障，计入熔断
 *   CallNotPermitted / Bulkhead→ 503             熔断打开或舱壁已满，快速失败
 *   其他 RuntimeException       → 500 + traceId   兜底，未知错误
 * </pre>
 *
 * <p><b>为什么业务失败也返回 200</b>：HTTP 状态码表达的是「请求有没有被正确处理」，
 * 「库存不足」是<strong>被正确处理</strong>后的业务结论，不是服务端错误。
 * 真正的错误语义放在响应体的 {@code code} 字段里。
 *
 * <p><b>traceId 放响应头而不是文案里</b>：用户看到的文案保持干净，
 * 排障时从 {@code X-Trace-Id} 取值即可串联全链路日志。
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    /** 业务失败：请求被正确处理了，只是业务上没做成。不熔断、不告警。 */
    @ExceptionHandler(BusinessException.class)
    public Result handleBusinessException(BusinessException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /** 系统故障：依赖不可用。计入熔断，返回 503 让客户端可以退避重试。 */
    @ExceptionHandler(SystemException.class)
    public ResponseEntity<Result> handleSystemException(SystemException e) {
        String traceId = TraceContext.current();
        log.error("系统异常 code={} traceId={} msg={}", e.getCode(), traceId, e.getMessage(), e);
        return build(traceId, HttpStatus.SERVICE_UNAVAILABLE, e.getErrorCode(), e.getMessage());
    }

    /**
     * 熔断打开 / 舱壁已满：依赖已被判定为不可用，这里做的是「快速失败」——
     * 与其让请求排队等死，不如立刻告诉用户稍后再来。
     */
    @ExceptionHandler({CallNotPermittedException.class, BulkheadFullException.class})
    public ResponseEntity<Result> handleResilienceRejection(RuntimeException e) {
        String traceId = TraceContext.current();
        log.warn("依赖被熔断或隔离，快速失败 type={} traceId={}", e.getClass().getSimpleName(), traceId);
        return build(traceId, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SYS_BUSY, ErrorCode.SYS_BUSY.getMessage());
    }

    /** 参数校验失败：取第一个字段错误，避免把整张校验表甩给前端 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result> handleValidException(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        String msg = errors.isEmpty()
                ? ErrorCode.PARAM_INVALID.getMessage()
                : errors.get(0).getField() + ": " + errors.get(0).getDefaultMessage();
        return build(TraceContext.current(), HttpStatus.BAD_REQUEST, ErrorCode.PARAM_INVALID, msg);
    }

    /** 兜底：未归类的异常一律视为未知系统错误。保留栈帧，方便定位漏网的异常类型。 */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result> handleRuntimeException(RuntimeException e) {
        String traceId = TraceContext.current();
        log.error("未归类异常 traceId={} type={}", traceId, e.getClass().getName(), e);
        return build(traceId, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.SYS_ERROR, ErrorCode.SYS_ERROR.getMessage());
    }

    private ResponseEntity<Result> build(String traceId, HttpStatus status, ErrorCode code, String msg) {
        HttpHeaders headers = new HttpHeaders();
        if (traceId != null) {
            headers.set(TraceContext.TRACE_ID_KEY, traceId);
        }
        return ResponseEntity.status(status).headers(headers).body(Result.fail(code, msg));
    }
}
