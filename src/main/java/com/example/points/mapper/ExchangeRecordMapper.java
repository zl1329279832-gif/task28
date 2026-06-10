package com.example.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.points.entity.ExchangeRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ExchangeRecordMapper extends BaseMapper<ExchangeRecord> {

    @Select("SELECT COUNT(*) FROM exchange_record WHERE member_id = #{memberId} " +
            "AND benefit_id = #{benefitId} AND status = 1 AND DATE(create_time) = CURDATE()")
    int countDailyExchange(@Param("memberId") Long memberId, @Param("benefitId") Long benefitId);

    @Select("SELECT COUNT(*) FROM exchange_record WHERE member_id = #{memberId} " +
            "AND benefit_id = #{benefitId} AND status = 1")
    int countTotalExchange(@Param("memberId") Long memberId, @Param("benefitId") Long benefitId);
}
