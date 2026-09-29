package com.zddp.ticket.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器，把异常统一映射成 {@link Result} 信封。
 *
 * <p>映射原则：
 * <ul>
 *   <li>业务异常按自身携带的 HTTP 状态与业务码返回，不打印堆栈（属于可预期结果）。</li>
 *   <li>请求体无法解析（未知字段、时间缺时区偏移、JSON 语法错误、类型不匹配）统一 400 VALIDATION_ERROR。</li>
 *   <li>未预期的异常返回 500 INTERNAL_ERROR，堆栈只写服务端日志，不返回给客户端，
 *       避免暴露 SQL、密码、Session 或 CSRF token。</li>
 * </ul>
 *
 * <p>注意：认证失败（401）与 CSRF 校验失败（403）由 Spring Security 过滤器链在进入
 * 控制器之前处理，不经过本处理器。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex) {
        log.warn("业务异常 code={} message={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .body(Result.<Void>error(ex.getCode(), ex.getMessage()));
    }

    /**
     * 请求体不可读。覆盖三类情况：包含未知字段（例如注入 publisherId、status）、
     * 时间字符串缺少时区偏移、JSON 语法或字段类型不合法。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return badRequest("请求体格式不正确，或包含不被接受的字段、时间缺少时区偏移");
    }

    /** 路径参数或查询参数类型不合法，例如 /performances/abc */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("参数类型不匹配 name={} value={}", ex.getName(), ex.getValue());
        return badRequest("参数 " + ex.getName() + " 类型不正确");
    }

    /** 必填查询参数缺失 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
        log.warn("缺少必填参数 name={}", ex.getParameterName());
        return badRequest("缺少必填参数 " + ex.getParameterName());
    }

    /** 控制器方法参数上的约束校验失败（Spring 6.1+ 由 HandlerMethodValidationException 表达） */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleMethodValidation(HandlerMethodValidationException ex) {
        log.warn("方法参数校验失败: {}", ex.getMessage());
        return badRequest("参数校验失败");
    }

    /** 服务层 @Validated 代理触发的约束校验失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("约束校验失败: {}", ex.getMessage());
        return badRequest("参数校验失败");
    }

    /** 兜底：未预期异常。只记录服务端日志，对外返回通用错误，不泄露内部细节。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        log.error("未预期异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.<Void>error("INTERNAL_ERROR", "服务器内部错误"));
    }

    private ResponseEntity<Result<Void>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.<Void>error("VALIDATION_ERROR", message));
    }
}
