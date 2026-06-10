package com.membership.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.membership.points.entity.PointsTransaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface PointsTransactionMapper extends BaseMapper<PointsTransaction> {

    @Select("SELECT COALESCE(SUM(points_amount), 0) FROM points_transaction " +
            "WHERE member_id = #{memberId} AND source = #{source} " +
            "AND transaction_type = 'EARN' AND created_at >= #{startOfMonth}")
    Long sumEarnedPointsInMonth(@Param("memberId") Long memberId,
                                @Param("source") String source,
                                @Param("startOfMonth") LocalDateTime startOfMonth);
}
