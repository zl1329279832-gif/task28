package com.membership.points.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.membership.points.common.constant.RedisKeyConstants;
import com.membership.points.dto.response.PointsAccountResponse;
import com.membership.points.service.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 缓存服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheServiceImpl implements CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final long POINTS_ACCOUNT_TTL = 30;
    private static final long BLACKLIST_TTL = 60;
    private static final long INVENTORY_TTL = 5;

    @Override
    public void putPointsAccount(Long memberId, PointsAccountResponse response) {
        try {
            String key = RedisKeyConstants.POINTS_ACCOUNT + memberId;
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, POINTS_ACCOUNT_TTL, TimeUnit.MINUTES);
            log.debug("Cached points account for memberId: {}", memberId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize PointsAccountResponse for memberId: {}", memberId, e);
        }
    }

    @Override
    public PointsAccountResponse getPointsAccount(Long memberId) {
        try {
            String key = RedisKeyConstants.POINTS_ACCOUNT + memberId;
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return null;
            }
            String json = value.toString();
            return objectMapper.readValue(json, PointsAccountResponse.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize PointsAccountResponse for memberId: {}", memberId, e);
            return null;
        }
    }

    @Override
    public void evictPointsAccount(Long memberId) {
        String key = RedisKeyConstants.POINTS_ACCOUNT + memberId;
        redisTemplate.delete(key);
        log.debug("Evicted points account cache for memberId: {}", memberId);
    }

    @Override
    public void putBlacklist(Long memberId, Set<String> blockTypes) {
        try {
            String key = RedisKeyConstants.BLACKLIST_MEMBER + memberId;
            String json = objectMapper.writeValueAsString(blockTypes);
            redisTemplate.opsForValue().set(key, json, BLACKLIST_TTL, TimeUnit.MINUTES);
            log.debug("Cached blacklist for memberId: {}", memberId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize blacklist for memberId: {}", memberId, e);
        }
    }

    @Override
    public Set<String> getBlacklist(Long memberId) {
        try {
            String key = RedisKeyConstants.BLACKLIST_MEMBER + memberId;
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return null;
            }
            String json = value.toString();
            return objectMapper.readValue(json, new TypeReference<Set<String>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize blacklist for memberId: {}", memberId, e);
            return null;
        }
    }

    @Override
    public void evictBlacklist(Long memberId) {
        String key = RedisKeyConstants.BLACKLIST_MEMBER + memberId;
        redisTemplate.delete(key);
        log.debug("Evicted blacklist cache for memberId: {}", memberId);
    }

    @Override
    public void evictBenefitInventory(String skuCode) {
        String key = RedisKeyConstants.BENEFIT_INVENTORY + skuCode;
        redisTemplate.delete(key);
        log.debug("Evicted benefit inventory cache for skuCode: {}", skuCode);
    }
}
