package com.geek.smartoffice.common.exception;

import com.geek.smartoffice.common.result.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 将 MVC 请求处理期间抛出的异常转换为统一 JSON 响应，并同时设置实际 HTTP 状态码。
 * {@code @ExceptionHandler} 按异常类型选择处理方法，具体处理器之外的异常落入最后的 Exception 兜底。
 * Security 过滤器链在进入 MVC 前产生的认证、授权失败，由其专用入口和处理器负责写响应。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    /*
     * instanceof 模式变量 error 仅在异常实现 ErrorResponse 时可用，从中保留框架给出的状态。
     * 其他列出的参数错误按 400 处理；switch 表达式直接计算对外消息，避免暴露底层异常详情。
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class,
            HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            NoHandlerFoundException.class, NoResourceFoundException.class})

    public ResponseEntity<Result<Void>> handleHttpContract(Exception exception) {
        int status = exception instanceof ErrorResponse error ? error.getStatusCode().value() : 400;
        String message = switch (status) {
            case 404 -> "请求地址不存在";
            case 405 -> "请求方法不支持";
            case 406 -> "请求的响应媒体类型不支持";
            case 415 -> "请求媒体类型不支持";
            default -> "请求参数格式错误";
        };
        return ResponseEntity.status(status).body(Result.error(status, message));
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.error(403,"权限不足"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicate(DuplicateKeyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.error(409,"请求ID或用户名已被使用"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(Result.error(exception.getStatus().value(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException exception) {
        /*
         * @Valid 请求体校验失败会进入这里；stream().findFirst() 只取一个字段错误，不拼接全部消息。
         * Optional.orElse(null) 处理没有字段错误的情况，后续使用统一默认提示，仍返回 400。
         */
        FieldError fieldError = exception.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = fieldError == null ? "请求参数错误" : fieldError.getDefaultMessage();
        return badRequest(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        return badRequest(exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return badRequest("请求体格式错误");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(Exception exception) {
        // 未预期异常的堆栈仅写入服务端日志；HTTP 500 与响应体 code 一致，对外只给固定提示。
        log.error("Unhandled exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "服务器内部错误"));
    }

    private ResponseEntity<Result<Void>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Result.error(400, message));
    }
}
