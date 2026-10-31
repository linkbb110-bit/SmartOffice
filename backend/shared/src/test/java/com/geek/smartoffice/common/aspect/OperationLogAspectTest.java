package com.geek.smartoffice.common.aspect;

import com.geek.smartoffice.common.annotation.OperationLog;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/*
 * 直接调用通知并模拟连接点，将验证范围限定为 proceed 的调用及返回、异常传播，不启动 Spring AOP 代理。
 * thenReturn/thenThrow 只为模拟对象设定行为，真正执行 proceed 的时机仍在 around 的 try 块中。
 */
class OperationLogAspectTest {

    @Test
    void returnsTargetResultAndCallsProceedOnce() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        OperationLog annotation = mock(OperationLog.class);
        Object expected = new Object();
        when(annotation.value()).thenReturn("测试操作");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toShortString()).thenReturn("Demo.run()");
        when(joinPoint.proceed()).thenReturn(expected);

        Object actual = new OperationLogAspect().around(joinPoint, annotation);

        assertSame(expected, actual);
        verify(joinPoint, times(1)).proceed();
    }

    @Test
    void rethrowsOriginalFailure() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        OperationLog annotation = mock(OperationLog.class);
        IllegalStateException failure = new IllegalStateException("boom");
        when(annotation.value()).thenReturn("测试操作");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toShortString()).thenReturn("Demo.run()");
        when(joinPoint.proceed()).thenThrow(failure);

        // lambda 将可能抛错的调用延后交给 assertThrows 执行；此断言检查异常类型，不单独验证异常实例相同。
        assertThrows(IllegalStateException.class,
                () -> new OperationLogAspect().around(joinPoint, annotation));
    }
}
