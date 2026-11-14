package com.doctrail.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doctrail.entity.AdminAccountEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

public interface AdminAccountMapper extends BaseMapper<AdminAccountEntity> {
//    id="adminAccountResult"：给这一套映射规则起一个名字，后面别的方法可以直接复用这个映射
//    eg.@ResultMap("adminAccountResult") // 直接引用名字！复用整套映射！
//       AdminAccountEntity selectById(@Param("id") Long id);
    @Select("SELECT id, username, password_hash, role "
            + "FROM admin WHERE username = #{username} LIMIT 1")
    @Results(id = "adminAccountResult", value = {
//            id=true：标记这一列是主键
            @Result(property = "id", column = "id", id = true),
//            实体字段username ↔ 数据库列username
            @Result(property = "username", column = "username"),
//            实体字段passwordHash ↔ 数据库列password_hash
            @Result(property = "passwordHash", column = "password_hash"),
//            实体字段role ↔ 数据库列role
            @Result(property = "role", column = "role")
    })
    AdminAccountEntity selectByUsername(@Param("username") String username);
}
