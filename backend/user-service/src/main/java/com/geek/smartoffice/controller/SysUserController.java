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

    @GetMapping("/me")
    public Result<AuthenticatedUser> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return Result.success(sysUserService.currentUser(user.userId()));
    }
}
