package com.geek.smartoffice.common.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    /**
     * Security 过滤器链在授权前调用此方法；OncePerRequestFilter 用请求标记避免同一次分派重复执行。
     * 仅在有 Bearer 请求头且上下文尚无认证时解析令牌，成功后建立本次请求的 Authentication。
     * SecurityContextHolder 将认证结果关联到当前执行线程，后续 URL 授权、方法授权及身份参数解析据此工作。
     * 这里只恢复令牌内身份，不查询用户表；无令牌或令牌无效时继续执行，由后续规则决定是否允许访问。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header)
                && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7);
            try {
                AuthenticatedUser user = jwtService.parseToken(token);
                /*
                 * 三参数构造器把身份和权限集合组成已认证的凭据；JWT 校验已完成，因此不再保存密码。
                 * hasRole("ADMIN") 实际匹配 ROLE_ADMIN，principal 则供 @AuthenticationPrincipal 注入。
                 */
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        user,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ignored) {
                // 解析失败只清理认证状态，不在此写响应；受保护路径稍后通过认证入口返回 401。
                SecurityContextHolder.clearContext();
            }
        }
        // 把控制权交给后续过滤器及 MVC；放行路径即使携带无效令牌也可以继续到达控制器。
        filterChain.doFilter(request, response);
    }
}
