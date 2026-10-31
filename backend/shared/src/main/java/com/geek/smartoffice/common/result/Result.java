package com.geek.smartoffice.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应体：T 表示本次业务数据的类型，例如 {@code Result<String>} 的 data 是令牌字符串。
 * record 自动提供构造器、code()/message()/data() 等访问方法；组件引用创建后不能重新赋值。
 * 静态工厂上的 {@code <T>} 单独声明方法类型参数，由调用参数或接收类型推断；Void 表示没有业务数据。
 * JsonInclude.ALWAYS 保留值为 null 的 data，使成功和失败的字段结构一致。
 * 此处的 code 只是 JSON 字段，失败的实际 HTTP 状态还需由异常处理器或过滤器设置。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }

    public static Result<Void> success() {
        return new Result<>(200, "success", null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
