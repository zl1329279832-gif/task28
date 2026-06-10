package com.membership.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.membership.points.entity.IdempotentRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdempotentRecordMapper extends BaseMapper<IdempotentRecord> {
}
