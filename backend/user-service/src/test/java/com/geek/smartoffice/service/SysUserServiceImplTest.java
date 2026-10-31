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

/*
 * Mapper 使用模拟对象隔离数据库，而编码器使用真实 BCrypt，专门验证密码校验成功与失败后的服务结果。
 * any(Wrapper.class) 不约束查询条件内容，因此这些用例不能替代对真实 SQL、逻辑删除及唯一约束的验证。
 */
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
