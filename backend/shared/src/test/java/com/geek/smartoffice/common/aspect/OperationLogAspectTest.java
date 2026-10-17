package com.geek.smartoffice.common.aspect;

import com.geek.smartoffice.common.annotation.OperationLog;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

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

        assertThrows(IllegalStateException.class,
                () -> new OperationLogAspect().around(joinPoint, annotation));
    }
}
