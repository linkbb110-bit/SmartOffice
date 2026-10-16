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
        // Loading new rules does not clear Sentinel's JVM-wide rolling counters.
        // Keep prior API traffic in this regression, then isolate only this test's resource.
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
