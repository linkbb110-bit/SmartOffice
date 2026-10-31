package com.geek.smartoffice.common.aspect;

import com.geek.smartoffice.common.annotation.OperationLog;
import com.geek.smartoffice.common.security.AuthenticatedUser;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class OperationLogAspect {

    /**
     * Spring AOP 在命中 @OperationLog 的代理方法调用外层执行此通知，并绑定实际注解实例。
     * joinPoint 表示被拦截的调用，proceed() 继续执行后续拦截器及目标方法；本通知只调用它一次。
     * 成功时返回目标调用原有的结果对象，失败时记录异常并原样抛出，不把失败转换成成功响应。
     * Throwable 覆盖目标调用可能抛出的异常和错误，使外层异常处理、事务通知仍有机会按各自规则处理。
     * 耗时使用单调计时的 nanoTime 差值，不依赖会被校时调整的日历时间。
     */
    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        long started = System.nanoTime();
        String method = joinPoint.getSignature().toShortString();
        String username = currentUsername();
        try {
            Object result = joinPoint.proceed();
            log.info("operation={} user={} method={} status=success costMs={}",
                    operationLog.value(), username, method, elapsedMillis(started));
            return result;
        } catch (Throwable throwable) {
            log.warn("operation={} user={} method={} status=failure costMs={} errorType={} message={}",
                    operationLog.value(), username, method, elapsedMillis(started),
                    throwable.getClass().getSimpleName(), safeMessage(throwable));
            // 不包装异常，保留其原有类型和实例；是否回滚仍由实际事务配置决定。
            throw throwable;
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }
        /*
         * instanceof 的模式变量 user 只在类型匹配后可用，同时完成类型判断和转换。
         * JWT 身份从 principal 取用户名；其他认证实现则回退到 Authentication 的名称。
         */
        if (authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.username();
        }
        return authentication.getName();
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "-";
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }
}
