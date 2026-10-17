package com.geek.smartoffice.integration;

import com.geek.smartoffice.service.BookingWriteService;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes={DatabaseTestConfig.class,ConcurrentIdempotencyTest.BarrierConfig.class})
@ActiveProfiles("test")
@Sql({"/db-test.sql","/message-test.sql"})
class ConcurrentIdempotencyTest {
    @TestConfiguration static class BarrierConfig {
        @Bean MissBarrier initialMissBarrier(){return new MissBarrier();}
    }
    @Intercepts(@Signature(type=Executor.class,method="query",
            args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class MissBarrier implements Interceptor {
        final CountDownLatch initialMisses=new CountDownLatch(2);
        public Object intercept(Invocation invocation) throws Throwable {
            Object result=invocation.proceed();
            var statement=(MappedStatement)invocation.getArgs()[0];
            if(statement.getId().endsWith(".findByRequest")
                    && invocation.getArgs()[1] instanceof Map<?,?> parameters
                    && "concurrent-retry".equals(parameters.get("requestId"))
                    && result instanceof List<?> rows && rows.isEmpty()) {
                initialMisses.countDown();
                if(!initialMisses.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("both initial misses were not reached");
            }
            return result;
        }
    }
    @Autowired BookingWriteService service;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void reset(){
        jdbc.update("DELETE FROM booking_outbox");jdbc.update("DELETE FROM booking_order");jdbc.update("DELETE FROM meeting_room");
        jdbc.update("INSERT INTO meeting_room(id,room_name,capacity,equipment,status) VALUES(1,'test',10,'[]',1)");
    }
    @Test void twoRequestsThatInitiallyMissMustBothReturnTheCommittedOrder() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Long> retry=()->{
                var dto=BookingConcurrencyTest.booking(10,11);dto.setRequestId("concurrent-retry");
                return service.createBookingInsideLock(dto,1L);
            };
            Future<Long> first=pool.submit(retry),second=pool.submit(retry);
            Long firstId=first.get(20,TimeUnit.SECONDS),secondId=second.get(20,TimeUnit.SECONDS);
            assertEquals(firstId,secondId);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM booking_order",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM booking_outbox",Integer.class));
        } finally { pool.shutdownNow(); }
    }
}
