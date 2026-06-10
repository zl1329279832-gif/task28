package com.example.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.points.entity.PointsFreeze;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface PointsFreezeMapper extends BaseMapper<PointsFreeze> {

    @Update("UPDATE points_freeze SET status = #{newStatus}, update_time = NOW() " +
            "WHERE freeze_no = #{freezeNo} AND status = #{expectedStatus}")
    int updateStatusCAS(@Param("freezeNo") String freezeNo,
                        @Param("expectedStatus") Integer expectedStatus,
                        @Param("newStatus") Integer newStatus);
}
