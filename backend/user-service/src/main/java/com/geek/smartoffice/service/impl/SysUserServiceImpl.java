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

    /**
     * 按用户名读取账号后用 PasswordEncoder.matches 比对明文与数据库散列，成功返回供签发令牌的实体。
     * {@code LambdaQueryWrapper<SysUser>} 限定查询实体，SysUser::getUsername 提供属性引用，由框架解析为列条件。
     * last 的固定 LIMIT 1 追加在查询尾部；用户名唯一约束负责防止重名，而非依靠此限制保证唯一。
     * 账号不存在、密码为空、超过字节上限或校验不匹配均抛出同一种 401 业务错误，不区分对外原因。
     */
    @Override
    public SysUser login(String username, String rawPassword) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("LIMIT 1"));
        // || 从左到右短路：空用户、空密码或超长密码会提前失败，不继续读取散列并调用 BCrypt。
        if (user == null || rawPassword == null
                || rawPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return user;
    }

    /**
     * 接收已通过注册 DTO 校验的用户名和明文密码，仅保存 BCrypt 散列并固定授予普通用户角色。
     * 不先查询是否重名，直接依靠数据库用户名唯一键裁决，避免“查询后再插入”之间的并发竞争。
     * 重名（包括仍占用用户名的逻辑删除行）触发 DuplicateKeyException，由全局处理器返回 409。
     * insert 成功后，MyBatis-Plus 将自增主键回填到实体，方法返回该 ID，不向外返回密码散列。
     */
    @Override
    public Long register(String username, String rawPassword) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(UserRole.USER.name());
        user.setIsDeleted(0);
        sysUserMapper.insert(user);
        return user.getId();
    }

    /**
     * 使用认证身份中的用户 ID 回查当前记录，返回不含密码的 AuthenticatedUser，而非直接回显令牌载荷。
     * MyBatis-Plus 的 selectById 结合 @TableLogic 排除逻辑删除用户；空 ID 或查不到记录也按身份失效处理。
     * 对名称和角色继续做完整性校验，未知角色名的枚举转换异常统一改为 401 业务错误。
     * 此处只刷新 /users/me 的返回资料，不会全局撤销既有 JWT，也不会重建本次请求的 SecurityContext。
     */
    @Override
    public AuthenticatedUser currentUser(Long userId) {
        SysUser user = userId == null ? null : sysUserMapper.selectById(userId);
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
