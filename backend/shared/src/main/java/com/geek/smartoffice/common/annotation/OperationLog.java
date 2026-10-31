package com.geek.smartoffice.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 为方法声明操作名称，供 OperationLogAspect 的环绕通知绑定并写入日志。
 * METHOD 限定使用位置，RUNTIME 让注解在运行时仍可被切面读取；注解本身不执行日志逻辑。
 * 只有调用进入 Spring AOP 代理并命中切点时才会记录，不能据此认为任意方法调用都会被拦截。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {
    String value();
}
