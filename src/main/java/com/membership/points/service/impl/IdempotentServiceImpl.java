package com.membership.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.membership.points.entity.IdempotentRecord;
import com.membership.points.mapper.IdempotentRecordMapper;
import com.membership.points.service.IdempotentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 幂等性服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentServiceImpl implements IdempotentService {

    private final IdempotentRecordMapper idempotentRecordMapper;

    @Override
    public boolean checkAndMark(String idempotentKey, String businessType) {
        IdempotentRecord record = new IdempotentRecord();
        record.setIdempotentKey(idempotentKey);
        record.setBusinessType(businessType);
        record.setResultStatus("PROCESSING");
        record.setCreatedAt(LocalDateTime.now());
        record.setExpireAt(LocalDateTime.now().plusDays(7));

        try {
            idempotentRecordMapper.insert(record);
            log.debug("Idempotent key marked as PROCESSING: {}", idempotentKey);
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("Duplicate idempotent key detected: {}", idempotentKey);
            return false;
        }
    }

    @Override
    public String getCachedResult(String idempotentKey) {
        LambdaQueryWrapper<IdempotentRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IdempotentRecord::getIdempotentKey, idempotentKey);
        IdempotentRecord record = idempotentRecordMapper.selectOne(wrapper);
        if (record == null) {
            return null;
        }
        return record.getResultData();
    }

    @Override
    public void markResult(String idempotentKey, String resultData) {
        LambdaQueryWrapper<IdempotentRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IdempotentRecord::getIdempotentKey, idempotentKey);
        IdempotentRecord record = idempotentRecordMapper.selectOne(wrapper);
        if (record != null) {
            record.setResultStatus("SUCCESS");
            record.setResultData(resultData);
            idempotentRecordMapper.updateById(record);
            log.debug("Idempotent key marked as SUCCESS: {}", idempotentKey);
        }
    }

    @Override
    public void cleanExpired() {
        LambdaQueryWrapper<IdempotentRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(IdempotentRecord::getExpireAt, LocalDateTime.now());
        int deleted = idempotentRecordMapper.delete(wrapper);
        log.info("Cleaned {} expired idempotent records", deleted);
    }
}
