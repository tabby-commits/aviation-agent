package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * @description 针对表【users】的数据库操作Mapper
 * @Entity com.kama.jchatmind.model.entity.User
 */
@Mapper
public interface UserMapper {

    User selectByUsername(@Param("username") String username);
}
