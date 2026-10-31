
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

/**
 * 将创建预约的远程复核与写入流程包在同一个 Sentinel 资源入口中，按配置的 QPS 控制准入。
 * 此处使用进程内流控规则，每个服务实例独立统计；拒绝的请求不会继续执行预约 Lambda。
 */
@Component
public class BookingTrafficGuard {

    // 规则初始化和 execute 必须使用同一资源名，Sentinel 才能把入口访问匹配到这条规则。
    // 这是业务资源标识，不是 HTTP 路径；仅调用 execute 的创建预约流程计入此资源。
    private static final String RESOURCE =
            "smartoffice:booking:create";

    private final double qps;

    /**
     * Spring 通过 {@code @Value} 读取 smartoffice.booking.qps；未配置时使用 20，
     * 作为 Sentinel 按秒统计的请求通过阈值。构造时校验有限正数，配置错误会阻止 Bean 创建，
     * 避免在接收预约后才暴露无效限流配置。
     */
    public BookingTrafficGuard(
            @Value("${smartoffice.booking.qps:20}") double qps) {

        // 启动时拒绝无效阈值，避免错误配置影响限流。
        if (!Double.isFinite(qps) || qps <= 0) {
            throw new IllegalArgumentException(
                    "booking qps must be positive"
            );
        }

        this.qps = qps;
    }

    /**
     * {@code @PostConstruct} 在依赖注入完成后执行一次，让配置好的限流规则在处理请求前生效。
     * 此处只加载创建预约的一条 QPS 规则；loadRules 替换本进程的流控规则集合，
     * 并不是向某个跨实例的集中限流计数器注册请求。
     */
    @PostConstruct
    public void initialize() {

        // 先用资源名构造规则，再选择 QPS 统计维度并设置阈值。
        // 规则加载只发生在初始化阶段，每次 execute 进入资源时才实际检查能否放行。
        var rule = new FlowRule(RESOURCE);

        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);

        rule.setCount(qps);

        // 将规则列表加载到 Sentinel；loadRules 会替换当前进程已有的流控规则列表。
        FlowRuleManager.loadRules(List.of(rule));
    }

    /**
     * 接收一段尚未执行的预约操作，准入成功时原样返回它的结果。
     * {@code Supplier<T>} 的 get() 才会运行 Controller 传入的 Lambda；在此之前，
     * Lambda 中的用户服务调用、加锁和订单写入都没有发生。
     * {@code T} 由调用者返回值推断，例如创建预约时是 Long，使此组件只管理准入而不绑定订单类型。
     * entry 阶段抛出的 BlockException 转成 429 业务异常；业务自身的异常继续向外传播，
     * 由事务代理和统一异常处理分别负责回滚与 HTTP 响应。
     */
    public <T> T execute(Supplier<T> booking) {

        // try 的资源表达式先执行 SphU.entry，再进入代码块调用 get；被限流时不会进入代码块。
        // Entry 记录本次资源访问，try-with-resources 在正常返回或业务抛错时都会自动关闭它，
        // 配对完成 Sentinel 的资源退出与访问统计，避免提前 return 遗漏退出。
        try (Entry ignored = SphU.entry(RESOURCE)) {

            return booking.get();

        } catch (BlockException ex) {

            // 将 Sentinel 的拒绝信号转换为项目统一异常，最终由异常处理器写出 HTTP 429。
            // 对入口限流的请求，Supplier 尚未执行，调用方可稍后重试创建预约。
            throw new BusinessException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "预约请求过多，请稍后重试"
            );
        }
    }
}
