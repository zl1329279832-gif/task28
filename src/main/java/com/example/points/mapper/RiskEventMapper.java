package com.example.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.points.entity.RiskEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface RiskEventMapper extends BaseMapper<RiskEvent> {

    @Select("SELECT * FROM risk_event WHERE event_no = #{eventNo}")
    RiskEvent selectByEventNo(@Param("eventNo") String eventNo);
}
