package com.geek.smartoffice.common.security;

import com.geek.smartoffice.common.enums.UserRole;

public record AuthenticatedUser(Long userId, String username, UserRole role) {
}
