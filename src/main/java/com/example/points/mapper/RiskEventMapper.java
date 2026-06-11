package com.example.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.points.entity.RiskEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

public interface RiskEventMapper extends BaseMapper<RiskEvent> {

    @Select("SELECT COUNT(*) FROM points_flow WHERE member_id = #{memberId} " +
            "AND points_change > 0 AND create_time >= #{since}")
    int countMemberClaimsSince(@Param("memberId") Long memberId, @Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM points_flow WHERE member_id = #{memberId} " +
            "AND event_type = 'REFUND' AND create_time >= #{since}")
    int countMemberRefundsSince(@Param("memberId") Long memberId, @Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM points_flow WHERE member_id = #{memberId} " +
            "AND points_change > 0 AND event_type IN ('REGISTER','PURCHASE','CHECKIN','ACTIVITY') " +
            "AND create_time >= #{since}")
    int countMemberIssuancesSince(@Param("memberId") Long memberId, @Param("since") LocalDateTime since);

    @Insert("INSERT IGNORE INTO risk_event (pool_id, member_id, event_type, flow_id, " +
            "detail, status, idempotent_key, create_time) VALUES " +
            "(#{poolId}, #{memberId}, #{eventType}, #{flowId}, #{detail}, " +
            "#{status}, #{idempotentKey}, #{createTime})")
    int insertIgnoreDuplicate(RiskEvent event);
}
