package com.example.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.points.entity.PointsFlow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

public interface PointsFlowMapper extends BaseMapper<PointsFlow> {

    @Select("SELECT COUNT(*) FROM points_flow WHERE member_id = #{memberId} " +
            "AND event_type = 'CHECKIN' AND DATE(create_time) = CURDATE()")
    int countTodayCheckin(@Param("memberId") Long memberId);

    @Select("SELECT COALESCE(SUM(points_change), 0) FROM points_flow " +
            "WHERE member_id = #{memberId} AND points_change > 0 AND event_type != 'REFUND' " +
            "AND YEAR(create_time) = YEAR(NOW()) AND MONTH(create_time) = MONTH(NOW())")
    Long sumMonthEarned(@Param("memberId") Long memberId);

    @Select("SELECT COUNT(*) FROM points_flow WHERE member_id = #{memberId} " +
            "AND event_type = 'REDEEM' AND remark LIKE CONCAT('%', #{benefitId}, '%') " +
            "AND DATE(create_time) = CURDATE()")
    int countDailyExchange(@Param("memberId") Long memberId, @Param("benefitId") Long benefitId);

    @Select("SELECT COUNT(*) FROM points_flow WHERE member_id = #{memberId} " +
            "AND points_change > 0 AND create_time >= #{since}")
    int countRecentEarn(@Param("memberId") Long memberId,
                        @Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM points_flow WHERE member_id = #{memberId} " +
            "AND event_type = 'REFUND' AND create_time >= #{since}")
    int countRecentRefund(@Param("memberId") Long memberId,
                          @Param("since") LocalDateTime since);
}
