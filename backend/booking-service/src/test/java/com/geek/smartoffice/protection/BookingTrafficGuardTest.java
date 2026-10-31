package com.geek.smartoffice.protection;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import com.geek.smartoffice.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BookingTrafficGuardTest {
    // Sentinel 规则和计数都由当前 JVM 共享；测试前保存规则，结束后恢复，避免低阈值影响其他测试。
    // 滑动窗口统计还需单独重置，本测试只清理预约资源，不清空其他资源的计数。
    private List<FlowRule> previousRules;
    private static void resetBookingMetrics() {
        var node=ClusterBuilderSlot.getClusterNode("smartoffice:booking:create");
        if(node!=null) node.reset();
    }
    @AfterEach void restoreRules(){
        FlowRuleManager.loadRules(previousRules);
        resetBookingMetrics();
    }
    @org.junit.jupiter.api.BeforeEach void isolateEarlierApiTraffic() {
        previousRules=List.copyOf(FlowRuleManager.getRules());
        var previous=new BookingTrafficGuard(20);
        previous.initialize();
        previous.execute(()->0L);
        previous.execute(()->0L);
        // 重新加载规则不会清空 JVM 中的滑动窗口计数；先模拟已有流量，再只重置本测试的资源计数。
        resetBookingMetrics();
    }
    @Test void secondRequestInSameWindowIsLimitedAndDoesNotExecuteBooking() {
        var guard=new BookingTrafficGuard(1);
        guard.initialize();
        assertEquals(42L,guard.execute(()->42L));
        assertEquals(429,assertThrows(BusinessException.class,
                ()->guard.execute(()->{fail("limited request must not execute booking");return 0L;})).getStatus().value());
    }
}
