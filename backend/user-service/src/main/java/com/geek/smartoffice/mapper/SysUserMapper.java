package com.geek.smartoffice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.geek.smartoffice.model.entity.SysUser;

/*
 * BaseMapper<SysUser> 把通用增删查改的实体类型固定为 SysUser，映射依据该实体上的表名和主键注解。
 * UserApplication 的 @MapperScan 为此接口注册 MyBatis 代理，调用方法时才实际执行映射 SQL。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {
}
