package com.geek.smartoffice.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务层用此异常同时传递预期的 HTTP 状态和面向调用方的失败原因。
 * 它是非受检异常，调用链无需逐层声明 throws；进入 MVC 异常处理后由 GlobalExceptionHandler 组装响应。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
