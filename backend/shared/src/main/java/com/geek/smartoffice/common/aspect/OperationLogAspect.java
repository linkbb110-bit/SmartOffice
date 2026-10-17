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
