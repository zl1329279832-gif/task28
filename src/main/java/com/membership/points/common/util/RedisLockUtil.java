package com.membership.points.common.util;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis分布式锁工具类
 * 基于Redisson实现
 */
@Slf4j
@Component
public class RedisLockUtil {

    private final RedissonClient redissonClient;

    public RedisLockUtil(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试获取分布式锁
     *
     * @param key         锁的key
     * @param waitTimeMs  等待获取锁的最大时间（毫秒）
     * @param leaseTimeMs 锁的持有时间（毫秒），超时自动释放
     * @return 是否获取成功
     */
    public boolean tryLock(String key, long waitTimeMs, long leaseTimeMs) {
        RLock lock = redissonClient.getLock(key);
        try {
            return lock.tryLock(waitTimeMs, leaseTimeMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            log.error("获取分布式锁被中断, key: {}", key, e);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放分布式锁
     * 只有当前线程持有锁时才释放，避免释放其他线程的锁
     *
     * @param key 锁的key
     */
    public void unlock(String key) {
        RLock lock = redissonClient.getLock(key);
        if (lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.error("释放分布式锁异常, key: {}", key, e);
            }
        }
    }
}
