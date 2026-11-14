package com.doctrail.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doctrail.entity.UserAccountEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {
    @Select("SELECT id, username, password_hash, role "
            + "FROM `user` WHERE username = #{username} LIMIT 1")
    @Results(id = "userAccountResult", value = {
            @Result(property = "id", column = "id", id = true),
            @Result(property = "username", column = "username"),
            @Result(property = "passwordHash", column = "password_hash"),
            @Result(property = "role", column = "role")
    })
    UserAccountEntity selectByUsername(@Param("username") String username);
}
