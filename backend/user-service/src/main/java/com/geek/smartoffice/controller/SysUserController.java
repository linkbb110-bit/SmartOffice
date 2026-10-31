package com.geek.smartoffice.controller;

import com.geek.smartoffice.common.enums.UserRole;
import com.geek.smartoffice.common.result.Result;
import com.geek.smartoffice.common.security.JwtService;
import com.geek.smartoffice.common.security.AuthenticatedUser;
import com.geek.smartoffice.model.dto.LoginDTO;
import com.geek.smartoffice.model.dto.RegisterDTO;
import com.geek.smartoffice.model.entity.SysUser;
import com.geek.smartoffice.service.SysUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService sysUserService;
    private final JwtService jwtService;

    /**
     * MVC 将 JSON 请求体转换成 LoginDTO 后执行 @Valid，校验失败会在进入方法前交给异常处理器。
     * 服务先用数据库账号和密码散列完成校验，再以查询所得的 ID、用户名和角色签发 JWT。
     * 返回的 {@code Result<String>} 将令牌放在 data 中，客户端后续需通过 Authorization: Bearer 请求头携带。
     */
    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody LoginDTO dto) {
        SysUser user = sysUserService.login(dto.getUsername(), dto.getPassword());
        String token = jwtService.createToken(
                user.getId(),
                user.getUsername(),
                UserRole.valueOf(user.getRole())
        );
        return Result.success(token);
    }

    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.success(sysUserService.register(dto.getUsername(), dto.getPassword()));
    }

    /*
     * @AuthenticationPrincipal 从当前 Authentication 的 principal 解析参数，用户 ID 不取自客户端查询参数。
     * 令牌只提供回查 ID，服务会重新读取数据库资料；删除用户及无效角色会在回查阶段转成 401。
     * 返回值是无密码的身份对象；此次回查不会修改过滤器已基于令牌建立的认证权限。
     */
    @GetMapping("/me")
    public Result<AuthenticatedUser> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return Result.success(sysUserService.currentUser(user.userId()));
    }
}
