package com.membership.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.membership.points.entity.PointsBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PointsBatchMapper extends BaseMapper<PointsBatch> {

    @Select("SELECT * FROM points_batch WHERE member_id = #{memberId} " +
            "AND expired = 0 AND remaining_points > 0 ORDER BY expire_at ASC")
    List<PointsBatch> selectActiveBatchesByMemberId(@Param("memberId") Long memberId);
}
