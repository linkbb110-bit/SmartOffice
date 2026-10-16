package com.geek.smartoffice.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.mapper.SysUserMapper;
import com.geek.smartoffice.model.entity.SysUser;
import com.geek.smartoffice.service.impl.SysUserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SysUserServiceImplTest {

    @Test
    void validBcryptPasswordLogsIn() {
        SysUserMapper mapper = mock(SysUserMapper.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword(encoder.encode("123456"));
        user.setRole("ADMIN");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(user);

        SysUser actual = new SysUserServiceImpl(mapper, encoder).login("admin", "123456");

        assertEquals(1L, actual.getId());
    }

    @Test
    void wrongPasswordReturns401() {
        SysUserMapper mapper = mock(SysUserMapper.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        SysUser user = new SysUser();
        user.setUsername("admin");
        user.setPassword(encoder.encode("123456"));
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(user);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new SysUserServiceImpl(mapper, encoder).login("admin", "wrong"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
    }
}
