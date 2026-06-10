package com.example.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.points.entity.BudgetPool;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

public interface BudgetPoolMapper extends BaseMapper<BudgetPool> {

    @Update("UPDATE budget_pool SET used_budget = used_budget + #{points}, " +
            "daily_used = daily_used + #{points}, monthly_used = monthly_used + #{points}, " +
            "update_time = NOW() WHERE id = #{poolId} AND status = 1 " +
            "AND (total_budget - used_budget) >= #{points} " +
            "AND start_time <= NOW() AND end_time >= NOW() " +
            "AND (daily_cap = 0 OR (daily_cap - daily_used) >= #{points}) " +
            "AND (monthly_cap = 0 OR (monthly_cap - monthly_used) >= #{points})")
    int reserveBudget(@Param("poolId") Long poolId, @Param("points") long points);

    @Update("UPDATE budget_pool SET used_budget = GREATEST(used_budget - #{points}, 0), " +
            "daily_used = GREATEST(daily_used - #{points}, 0), " +
            "monthly_used = GREATEST(monthly_used - #{points}, 0), " +
            "update_time = NOW() WHERE id = #{poolId}")
    int releaseBudget(@Param("poolId") Long poolId, @Param("points") long points);

    @Update("UPDATE budget_pool SET used_budget = used_budget + #{points}, update_time = NOW() " +
            "WHERE id = #{poolId} AND status = 1 AND (total_budget - used_budget) >= #{points}")
    int consumeBudget(@Param("poolId") Long poolId, @Param("points") long points);

    @Update("UPDATE budget_pool SET used_budget = GREATEST(used_budget - #{points}, 0), " +
            "update_time = NOW() WHERE id = #{poolId}")
    int restoreBudget(@Param("poolId") Long poolId, @Param("points") long points);

    @Update("UPDATE budget_pool SET daily_used = 0, daily_reset_date = #{resetDate}, " +
            "update_time = NOW() WHERE daily_reset_date IS NULL OR daily_reset_date < #{resetDate}")
    int resetDailyUsed(@Param("resetDate") LocalDate resetDate);

    @Update("UPDATE budget_pool SET monthly_used = 0, monthly_reset_date = #{resetDate}, " +
            "update_time = NOW() WHERE monthly_reset_date IS NULL OR monthly_reset_date < #{resetDate}")
    int resetMonthlyUsed(@Param("resetDate") LocalDate resetDate);

    @Update("UPDATE budget_pool SET status = 4, update_time = NOW() WHERE status = 1 AND end_time < NOW()")
    int markExpiredPools();

    @Update("UPDATE budget_pool SET status = 2, update_time = NOW() WHERE status = 1 AND used_budget >= total_budget")
    int markExhaustedPools();
}
