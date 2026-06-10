package com.membership.points.service;

import com.membership.points.dto.response.PointsAccountResponse;
import com.membership.points.entity.PointsAccount;

/**
 * 积分账户服务接口
 */
public interface PointsAccountService {

    /**
     * 获取或创建积分账户
     * 如果账户不存在则自动创建一个零余额账户
     *
     * @param memberId 会员ID
     * @return 积分账户
     */
    PointsAccount getOrCreateAccount(Long memberId);

    /**
     * 获取积分账户响应（带会员和等级信息）
     *
     * @param memberId 会员ID
     * @return 积分账户响应
     */
    PointsAccountResponse getAccountResponse(Long memberId);

    /**
     * 增加可用积分
     *
     * @param memberId 会员ID
     * @param points   积分数量
     * @param version  当前版本号（乐观锁）
     */
    void addPoints(Long memberId, long points, int version);

    /**
     * 扣减可用积分
     *
     * @param memberId 会员ID
     * @param points   积分数量
     * @param version  当前版本号（乐观锁）
     */
    void deductPoints(Long memberId, long points, int version);

    /**
     * 冻结积分
     *
     * @param memberId 会员ID
     * @param points   积分数量
     * @param version  当前版本号（乐观锁）
     */
    void freezePoints(Long memberId, long points, int version);

    /**
     * 解冻并扣减积分
     *
     * @param memberId 会员ID
     * @param points   积分数量
     * @param version  当前版本号（乐观锁）
     */
    void unfreezeAndDeduct(Long memberId, long points, int version);

    /**
     * 解冻并恢复积分
     *
     * @param memberId 会员ID
     * @param points   积分数量
     * @param version  当前版本号（乐观锁）
     */
    void unfreezeAndRestore(Long memberId, long points, int version);

    /**
     * 过期积分扣减
     *
     * @param memberId 会员ID
     * @param points   积分数量
     * @param version  当前版本号（乐观锁）
     */
    void expirePoints(Long memberId, long points, int version);
}
