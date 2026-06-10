package com.example.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.points.entity.Blacklist;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface BlacklistMapper extends BaseMapper<Blacklist> {

    @Select("SELECT COUNT(*) > 0 FROM blacklist WHERE member_id = #{memberId} " +
            "AND status = 1 AND (expire_time IS NULL OR expire_time > NOW())")
    boolean isBlacklisted(@Param("memberId") Long memberId);
}
