package com.membership.points.service;

import com.membership.points.dto.response.PointsAccountResponse;

import java.util.Set;

/**
 * 缓存服务接口
 * 统一管理Redis缓存操作
 */
public interface CacheService {

    /**
     * 缓存积分账户信息
     *
     * @param memberId 会员ID
     * @param response 积分账户响应
     */
    void putPointsAccount(Long memberId, PointsAccountResponse response);

    /**
     * 获取缓存的积分账户信息
     *
     * @param memberId 会员ID
     * @return 积分账户响应，缓存未命中返回null
     */
    PointsAccountResponse getPointsAccount(Long memberId);

    /**
     * 清除积分账户缓存
     *
     * @param memberId 会员ID
     */
    void evictPointsAccount(Long memberId);

    /**
     * 缓存黑名单信息
     *
     * @param memberId   会员ID
     * @param blockTypes 黑名单类型集合
     */
    void putBlacklist(Long memberId, Set<String> blockTypes);

    /**
     * 获取缓存的黑名单信息
     *
     * @param memberId 会员ID
     * @return 黑名单类型集合，缓存未命中返回null
     */
    Set<String> getBlacklist(Long memberId);

    /**
     * 清除黑名单缓存
     *
     * @param memberId 会员ID
     */
    void evictBlacklist(Long memberId);

    /**
     * 清除权益库存缓存
     *
     * @param skuCode SKU编码
     */
    void evictBenefitInventory(String skuCode);
}
