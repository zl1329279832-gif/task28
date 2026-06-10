package com.example.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.points.entity.BudgetUsageLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface BudgetUsageLogMapper extends BaseMapper<BudgetUsageLog> {

    @Select("SELECT COALESCE(SUM(points), 0) FROM budget_usage_log " +
            "WHERE pool_id = #{poolId} AND usage_type = 'OCCUPY' " +
            "AND DATE(create_time) = CURDATE()")
    Long sumDailyOccupied(@Param("poolId") Long poolId);

    @Select("SELECT COALESCE(SUM(points), 0) FROM budget_usage_log " +
            "WHERE pool_id = #{poolId} AND usage_type = 'OCCUPY' " +
            "AND YEAR(create_time) = YEAR(NOW()) AND MONTH(create_time) = MONTH(NOW())")
    Long sumMonthlyOccupied(@Param("poolId") Long poolId);

    @Select("SELECT COUNT(*) FROM budget_usage_log " +
            "WHERE member_id = #{memberId} AND usage_type = 'OCCUPY' " +
            "AND create_time >= #{since}")
    int countRecentOccupy(@Param("memberId") Long memberId,
                          @Param("since") java.time.LocalDateTime since);
}
