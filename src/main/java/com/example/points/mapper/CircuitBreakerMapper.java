package com.example.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.points.entity.CircuitBreaker;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface CircuitBreakerMapper extends BaseMapper<CircuitBreaker> {

    @Select("SELECT * FROM circuit_breaker WHERE pool_id = #{poolId}")
    CircuitBreaker selectByPoolId(@Param("poolId") Long poolId);

    @Select("SELECT * FROM circuit_breaker WHERE status = 'OPEN' " +
            "AND last_failure_time IS NOT NULL " +
            "AND DATE_ADD(last_failure_time, INTERVAL cooldown_minutes MINUTE) <= NOW()")
    List<CircuitBreaker> findOpenBreakersReadyForRecovery();

    @Update("UPDATE circuit_breaker SET status = #{newStatus}, " +
            "last_state_change = NOW(), update_time = NOW() " +
            "WHERE id = #{id} AND status = #{oldStatus}")
    int casTransition(@Param("id") Long id, @Param("oldStatus") String oldStatus,
                      @Param("newStatus") String newStatus);

    @Update("UPDATE circuit_breaker SET status = 'OPEN', failure_count = failure_count + 1, " +
            "last_failure_time = NOW(), last_state_change = NOW(), half_open_count = 0, " +
            "update_time = NOW() WHERE pool_id = #{poolId}")
    int tripBreaker(@Param("poolId") Long poolId);

    @Update("UPDATE circuit_breaker SET half_open_count = half_open_count + 1, " +
            "update_time = NOW() WHERE pool_id = #{poolId} AND status = 'HALF_OPEN'")
    int incrementHalfOpenCount(@Param("poolId") Long poolId);

    @Update("UPDATE circuit_breaker SET status = 'CLOSED', failure_count = 0, " +
            "half_open_count = 0, last_state_change = NOW(), update_time = NOW() " +
            "WHERE pool_id = #{poolId} AND status = 'HALF_OPEN'")
    int closeFromHalfOpen(@Param("poolId") Long poolId);

    @Update("UPDATE circuit_breaker SET status = 'OPEN', last_state_change = NOW(), " +
            "half_open_count = 0, last_failure_time = NOW(), update_time = NOW() " +
            "WHERE pool_id = #{poolId} AND status = 'HALF_OPEN'")
    int reopenFromHalfOpen(@Param("poolId") Long poolId);

    @Update("UPDATE circuit_breaker SET status = 'OPEN', failure_count = failure_count + 1, " +
            "last_failure_time = NOW(), last_state_change = NOW(), half_open_count = 0, " +
            "budget_released = #{budgetReleased}, update_time = NOW() " +
            "WHERE pool_id = #{poolId}")
    int tripBreakerWithBudgetFlag(@Param("poolId") Long poolId,
                                  @Param("budgetReleased") int budgetReleased);

    @Update("UPDATE circuit_breaker SET status = 'CLOSED', failure_count = 0, " +
            "half_open_count = 0, budget_released = 0, " +
            "last_state_change = NOW(), update_time = NOW() " +
            "WHERE pool_id = #{poolId} AND status = 'HALF_OPEN'")
    int closeFromHalfOpenResetBudget(@Param("poolId") Long poolId);
}
