package com.example.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.points.entity.Benefit;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface BenefitMapper extends BaseMapper<Benefit> {

    @Update("UPDATE benefit SET available_stock = available_stock - 1, update_time = NOW() " +
            "WHERE id = #{benefitId} AND available_stock > 0 AND status = 1")
    int decrementStock(@Param("benefitId") Long benefitId);

    @Update("UPDATE benefit SET available_stock = available_stock + 1, update_time = NOW() " +
            "WHERE id = #{benefitId}")
    int incrementStock(@Param("benefitId") Long benefitId);
}
