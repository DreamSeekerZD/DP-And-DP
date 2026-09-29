package com.zddp.ticket.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态与稳定英文业务码，由 {@link GlobalExceptionHandler} 统一转成响应信封。
 *
 * <p>只用于表达可预期的业务结果（校验失败、不可见、状态冲突等）。
 * 参数绑定失败、JSON 解析失败这类框架异常不包装成本异常，直接在异常处理器里映射。
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** 400 参数或字段不合法 */
    public static BusinessException validation(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    /**
     * 404 资源不可见。
     * 他人的运营资源（草稿、他人演出）也走这里，按“不可见”处理而不是 403，避免暴露资源是否存在。
     */
    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 409 草稿信息不完整，尚不满足发布条件 */
    public static BusinessException notReady(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "PERFORMANCE_NOT_READY", message);
    }

    /** 409 已发布/已下架演出的关键字段被篡改，整笔拒绝且不更新任何字段 */
    public static BusinessException fieldsLocked(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "PERFORMANCE_FIELDS_LOCKED", message);
    }

    /** 409 当前状态不允许该转换，例如下架后重新发布、草稿执行下架 */
    public static BusinessException stateConflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "PERFORMANCE_STATE_CONFLICT", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
