package com.geek.smartoffice.config;

import com.geek.smartoffice.common.security.JsonAccessDeniedHandler;
import com.geek.smartoffice.common.security.JsonAuthenticationEntryPoint;
import com.geek.smartoffice.common.security.JwtAuthenticationFilter;
import com.geek.smartoffice.common.security.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
/*
 * 开启方法拦截器，使控制器上的 @PreAuthorize 在调用方法前检查表达式；它与 URL 授权共同生效。
 * 例如 hasRole('ADMIN') 读取 Authentication 的 ROLE_ADMIN 权限，相关权限来自 JWT 过滤器。
 */
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JsonAuthenticationEntryPoint authenticationEntryPoint;
    private final JsonAccessDeniedHandler accessDeniedHandler;

    /**
     * Spring 启动时调用此 Bean 工厂，将 HttpSecurity 累积的规则构建为后续请求使用的过滤器链。
     * 各 lambda 接收对应配置器并设置规则，并非每次请求执行这里的配置代码；请求到来后才按规则匹配。
     * CSRF、表单登录及 Basic 认证在此禁用，登录接口自行返回 JWT，后续通过请求头恢复身份。
     * 无状态策略不从 HTTP Session 保存或恢复认证；JWT 过滤器须在授权判断前填充当前请求上下文。
     * 匿名认证失败与已认证权限不足分别使用 401、403 JSON 处理器，不走页面登录跳转。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   @Value("${smartoffice.docs.public:false}") boolean publicDocs) throws Exception {
        return http
                // 方法引用把 disable 作为配置动作交给框架调用；cors 则使用已注册的跨域规则 Bean。
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .requestMatchers(HttpMethod.POST, "/users/login", "/users/register").permitAll()
                            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    // 按声明顺序寻找匹配规则；文档开关只增加这些路径的 GET 放行，不放行同路径的 POST。
                    if (publicDocs) {
                        auth.requestMatchers(HttpMethod.GET, "/doc.html", "/webjars/**",
                                "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    }
                    auth.requestMatchers(HttpMethod.GET, "/orders/export").hasRole("ADMIN")
                            .anyRequest().authenticated();
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // 以标准用户名密码过滤器的位置为参照插入 JWT 过滤器，确保后续授权可以读取其认证结果。
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /*
     * 容器注册 PasswordEncoder 接口对应的 BCrypt 实现，业务服务通过构造器注入并使用同一编码方案。
     * encode 生成带盐散列，matches 根据已有散列校验明文，不能用两次 encode 的结果直接比较。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
