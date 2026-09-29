package com.zddp.ticket.common;

/**
 * 统一响应信封，所有接口共用。
 *
 * <p>成功：HTTP 200，code 固定为 {@code OK}，message 为“成功”，data 为结果对象；
 * 失败：用 HTTP 状态码表达错误类别（400/401/403/404/409/500），data 固定为 null，
 * code 为稳定英文业务码，message 为面向用户的中文说明。
 *
 * <p>不把所有错误包成 HTTP 200；data 为 null 时也保留字段，前端可依赖字段始终存在。
 *
 * <p>只改 Java 类名与自有工厂方法名（ok -> success），JSON 协议里的成功业务码仍是 OK，
 * 框架的 {@code ResponseEntity.ok(...)} 等不受影响。
 *
 * @param <T> 结果对象类型
 */
public class Result<T> {

    /** 稳定英文业务码，成功固定为 OK */
    private String code;

    /** 面向用户的中文说明，不包含 SQL、堆栈、Session 或 CSRF token */
    private String message;

    /** 结果对象；失败时为 null */
    private T data;

    public Result() {
    }

    public Result(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 构造成功响应 */
    public static <T> Result<T> success(T data) {
        return new Result<T>("OK", "成功", data);
    }

    /** 构造失败响应，HTTP 状态由调用方（异常处理器或安全组件）决定 */
    public static <T> Result<T> error(String code, String message) {
        return new Result<T>(code, message, null);
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
