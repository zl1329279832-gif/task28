package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.entity.Blacklist;
import com.example.points.mapper.BlacklistMapper;
import com.example.points.service.AuditLogService;
import com.example.points.service.BlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlacklistServiceImpl implements BlacklistService {

    private final BlacklistMapper blacklistMapper;
    private final AuditLogService auditLogService;
    private final RedissonClient redissonClient;

    @Override
    public void addToBlacklist(Long memberId, String reason, String operator, LocalDateTime expireTime) {
        Blacklist blacklist = Blacklist.builder()
                .memberId(memberId)
                .reason(reason)
                .status(1)
                .operator(operator)
                .expireTime(expireTime)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        blacklistMapper.insert(blacklist);
        invalidateCache(memberId);
        auditLogService.log("BLACKLIST", "ADD", String.valueOf(memberId), "MEMBER",
                null, reason, operator, null);
        log.info("Member {} added to blacklist by {}, reason: {}", memberId, operator, reason);
    }

    @Override
    public void removeFromBlacklist(Long memberId, String operator) {
        LambdaQueryWrapper<Blacklist> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Blacklist::getMemberId, memberId)
                .eq(Blacklist::getStatus, 1);
        List<Blacklist> records = blacklistMapper.selectList(wrapper);
        for (Blacklist record : records) {
            record.setStatus(0);
            record.setUpdateTime(LocalDateTime.now());
            blacklistMapper.updateById(record);
        }
        invalidateCache(memberId);
        auditLogService.log("BLACKLIST", "REMOVE", String.valueOf(memberId), "MEMBER",
                null, null, operator, null);
        log.info("Member {} removed from blacklist by {}", memberId, operator);
    }

    @Override
    public boolean isBlacklisted(Long memberId) {
        String cacheKey = "blacklist:" + memberId;
        RBucket<Boolean> bucket = redissonClient.getBucket(cacheKey);
        Boolean cached = bucket.get();
        if (cached != null) {
            return cached;
        }
        boolean result = blacklistMapper.isBlacklisted(memberId);
        bucket.set(result, Duration.ofMinutes(5));
        return result;
    }

    private void invalidateCache(Long memberId) {
        String cacheKey = "blacklist:" + memberId;
        RBucket<Object> bucket = redissonClient.getBucket(cacheKey);
        bucket.delete();
    }
}
