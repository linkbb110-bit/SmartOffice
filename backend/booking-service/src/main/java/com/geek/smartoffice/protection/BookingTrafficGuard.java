package com.geek.smartoffice.protection;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.geek.smartoffice.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.function.Supplier;

@Component
public class BookingTrafficGuard {
    private static final String RESOURCE="smartoffice:booking:create";
    private final double qps;
    public BookingTrafficGuard(@Value("${smartoffice.booking.qps:20}") double qps) {
        if(!Double.isFinite(qps) || qps<=0) throw new IllegalArgumentException("booking qps must be positive");
        this.qps=qps;
    }
    @PostConstruct
    public void initialize() {
        var rule=new FlowRule(RESOURCE);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS); rule.setCount(qps);
        FlowRuleManager.loadRules(List.of(rule));
    }
    public <T> T execute(Supplier<T> booking) {
        try(Entry ignored=SphU.entry(RESOURCE)) { return booking.get(); }
        catch(BlockException ex) { throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"预约请求过多，请稍后重试"); }
    }
}
