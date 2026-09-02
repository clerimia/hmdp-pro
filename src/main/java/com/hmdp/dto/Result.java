package com.hmdp.dto;

import com.hmdp.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一响应体。
 *
 * <p>{@code code} 为新增字段（P0 异常体系引入）：业务失败时携带 {@link ErrorCode} 的数字码，
 * 让前端能区分「库存不足」和「系统繁忙」，而不是只能展示一段文案。
 * 保留 {@code errorMsg} 与原有静态方法，老调用点无需改动。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    private Boolean success;
    /** 错误码，成功时为 null；取值见 com.hmdp.exception.ErrorCode */
    private Integer code;
    private String errorMsg;
    private Object data;
    private Long total;

    public static Result ok() {
        return new Result(true, null, null, null, null);
    }

    public static Result ok(Object data) {
        return new Result(true, null, null, data, null);
    }

    public static Result ok(List<?> data, Long total) {
        return new Result(true, null, null, data, total);
    }

    /** 兼容老调用点：只有文案、无错误码 */
    public static Result fail(String errorMsg) {
        return new Result(false, null, errorMsg, null, null);
    }

    /** 按错误码失败：业务错误与系统错误走同一出口，由错误码本身区分 */
    public static Result fail(ErrorCode errorCode) {
        return new Result(false, errorCode.getCode(), errorCode.getMessage(), null, null);
    }

    /** 按错误码失败并覆盖文案（需要补充上下文时使用） */
    public static Result fail(ErrorCode errorCode, String errorMsg) {
        return new Result(false, errorCode.getCode(), errorMsg, null, null);
    }
}
