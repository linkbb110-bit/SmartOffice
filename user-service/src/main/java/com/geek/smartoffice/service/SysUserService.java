package com.geek.smartoffice.service;

import com.geek.smartoffice.model.entity.SysUser;
import com.geek.smartoffice.common.security.AuthenticatedUser;

public interface SysUserService {
    SysUser login(String username, String rawPassword);
    Long register(String username, String rawPassword);
    AuthenticatedUser currentUser(Long userId);
}
