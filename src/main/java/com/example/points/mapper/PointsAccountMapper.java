package com.example.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.points.entity.PointsAccount;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

public interface PointsAccountMapper extends BaseMapper<PointsAccount> {

    @Update("UPDATE points_account SET available_points = available_points + #{points}, " +
            "total_earned = total_earned + #{points}, " +
            "monthly_earned = monthly_earned + #{points}, " +
            "update_time = NOW() WHERE member_id = #{memberId} AND status = 1")
    int addPoints(@Param("memberId") Long memberId, @Param("points") Long points);

    @Update("UPDATE points_account SET available_points = available_points - #{points}, " +
            "total_consumed = total_consumed + #{points}, " +
            "update_time = NOW() WHERE member_id = #{memberId} AND available_points >= #{points} AND status = 1")
    int deductPoints(@Param("memberId") Long memberId, @Param("points") Long points);

    @Update("UPDATE points_account SET available_points = available_points - #{points}, " +
            "frozen_points = frozen_points + #{points}, " +
            "update_time = NOW() WHERE member_id = #{memberId} AND available_points >= #{points} AND status = 1")
    int freezePoints(@Param("memberId") Long memberId, @Param("points") Long points);

    @Update("UPDATE points_account SET frozen_points = frozen_points - #{points}, " +
            "available_points = available_points + #{points}, " +
            "update_time = NOW() WHERE member_id = #{memberId} AND frozen_points >= #{points}")
    int unfreezePoints(@Param("memberId") Long memberId, @Param("points") Long points);

    @Update("UPDATE points_account SET frozen_points = frozen_points - #{points}, " +
            "total_consumed = total_consumed + #{points}, " +
            "update_time = NOW() WHERE member_id = #{memberId} AND frozen_points >= #{points}")
    int deductFrozenPoints(@Param("memberId") Long memberId, @Param("points") Long points);

    @Update("UPDATE points_account SET available_points = available_points - #{points}, " +
            "total_expired = total_expired + #{points}, " +
            "update_time = NOW() WHERE member_id = #{memberId} AND available_points >= #{points}")
    int expirePoints(@Param("memberId") Long memberId, @Param("points") Long points);

    @Update("UPDATE points_account SET available_points = available_points + #{points}, " +
            "update_time = NOW() WHERE member_id = #{memberId} AND status = 1")
    int refundPoints(@Param("memberId") Long memberId, @Param("points") Long points);

    @Update("UPDATE points_account SET monthly_earned = 0, monthly_reset_date = #{resetDate} " +
            "WHERE monthly_reset_date IS NULL OR monthly_reset_date < #{resetDate}")
    int resetMonthlyEarned(@Param("resetDate") LocalDate resetDate);
}
