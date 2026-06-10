package com.membership.points.service;

import com.membership.points.dto.request.BlacklistRequest;
import com.membership.points.entity.Blacklist;

import java.util.List;

/**
 * 黑名单服务接口
 */
public interface BlacklistService {

    /**
     * 添加黑名单
     *
     * @param request 黑名单请求
     */
    void addToBlacklist(BlacklistRequest request);

    /**
     * 移除黑名单
     *
     * @param memberId  会员ID
     * @param blockType 黑名单类型
     * @param operator  操作人
     */
    void removeFromBlacklist(Long memberId, String blockType, String operator);

    /**
     * 检查是否被特定类型黑名单限制
     *
     * @param memberId  会员ID
     * @param blockType 黑名单类型
     * @return true=被限制
     */
    boolean isBlocked(Long memberId, String blockType);

    /**
     * 检查是否被限制获取积分
     *
     * @param memberId 会员ID
     * @return true=被限制
     */
    boolean isBlockedForEarn(Long memberId);

    /**
     * 检查是否被限制兑换积分
     *
     * @param memberId 会员ID
     * @return true=被限制
     */
    boolean isBlockedForRedeem(Long memberId);

    /**
     * 获取会员的有效黑名单记录
     *
     * @param memberId 会员ID
     * @return 有效黑名单列表
     */
    List<Blacklist> getActiveBlacklist(Long memberId);
}
