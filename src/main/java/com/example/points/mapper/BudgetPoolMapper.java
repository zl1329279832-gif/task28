package com.example.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.points.entity.BudgetPool;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface BudgetPoolMapper extends BaseMapper<BudgetPool> {

    @Update("UPDATE budget_pool SET used_budget = used_budget + #{points}, " +
            "update_time = NOW() " +
            "WHERE id = #{poolId} AND status = 1 " +
            "AND (used_budget + #{points}) <= total_budget")
    int occupyBudget(@Param("poolId") Long poolId, @Param("points") Long points);

    @Update("UPDATE budget_pool SET used_budget = used_budget - #{points}, " +
            "update_time = NOW() " +
            "WHERE id = #{poolId} AND used_budget >= #{points}")
    int releaseBudget(@Param("poolId") Long poolId, @Param("points") Long points);

    @Update("UPDATE budget_pool SET frozen_budget = frozen_budget + #{points}, " +
            "update_time = NOW() " +
            "WHERE id = #{poolId}")
    int addFrozenBudget(@Param("poolId") Long poolId, @Param("points") Long points);

    @Update("UPDATE budget_pool SET frozen_budget = frozen_budget - #{points}, " +
            "update_time = NOW() " +
            "WHERE id = #{poolId} AND frozen_budget >= #{points}")
    int releaseFrozenBudget(@Param("poolId") Long poolId, @Param("points") Long points);

    @Update("UPDATE budget_pool SET status = #{status}, update_time = NOW() " +
            "WHERE id = #{poolId}")
    int updateStatus(@Param("poolId") Long poolId, @Param("status") Integer status);

    @Select("SELECT * FROM budget_pool WHERE activity_code = #{activityCode}")
    BudgetPool selectByActivityCode(@Param("activityCode") String activityCode);
}
