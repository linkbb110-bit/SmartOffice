package com.geek.smartoffice.common.security;

import com.geek.smartoffice.common.enums.UserRole;

/**
 * 供认证主体和当前用户响应共用的最小身份数据，不携带数据库密码散列。
 * JWT 解析得到的是令牌中的身份快照；/users/me 则会使用数据库当前记录重新构造此对象。
 */
public record AuthenticatedUser(Long userId, String username, UserRole role) {
}
