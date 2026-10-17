package com.geek.smartoffice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.common.enums.UserRole;
import com.geek.smartoffice.common.security.AuthenticatedUser;
import com.geek.smartoffice.mapper.SysUserMapper;
import com.geek.smartoffice.model.entity.SysUser;
import com.geek.smartoffice.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class SysUserServiceImpl implements SysUserService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public SysUser login(String username, String rawPassword) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("LIMIT 1"));
        if (user == null || rawPassword == null
                || rawPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return user;
    }

    @Override
    public Long register(String username, String rawPassword) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(UserRole.USER.name());
        user.setIsDeleted(0);
        // The unique database key arbitrates duplicate registrations, including races.
        // GlobalExceptionHandler translates DuplicateKeyException to HTTP 409.
        sysUserMapper.insert(user);
        return user.getId();
    }

    @Override
    public AuthenticatedUser currentUser(Long userId) {
        SysUser user = userId == null ? null : sysUserMapper.selectById(userId);
        // MyBatis-Plus applies @TableLogic to selectById; deleted users are absent.
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()
                || user.getRole() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不存在或已失效");
        }
        try {
            return new AuthenticatedUser(user.getId(), user.getUsername(), UserRole.valueOf(user.getRole()));
        } catch (IllegalArgumentException invalidRole) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不存在或已失效");
        }
    }
}
