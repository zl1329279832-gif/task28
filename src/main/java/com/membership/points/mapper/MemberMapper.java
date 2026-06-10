package com.membership.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.membership.points.entity.Member;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemberMapper extends BaseMapper<Member> {
}
