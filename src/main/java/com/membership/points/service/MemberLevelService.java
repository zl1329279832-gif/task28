package com.membership.points.service;

import com.membership.points.dto.response.MemberLevelResponse;
import com.membership.points.entity.MemberLevel;

/**
 * 会员等级服务接口
 */
public interface MemberLevelService {

    /**
     * 获取会员当前等级
     *
     * @param memberId 会员ID
     * @return 会员等级
     */
    MemberLevel getCurrentLevel(Long memberId);

    /**
     * 获取会员等级响应（含详细信息）
     *
     * @param memberId 会员ID
     * @return 会员等级响应
     */
    MemberLevelResponse getLevelResponse(Long memberId);

    /**
     * 检查并升级会员等级
     *
     * @param memberId 会员ID
     * @return true=等级发生变化
     */
    boolean checkAndUpgrade(Long memberId);

    /**
     * 重新计算会员等级
     * 基于最近12个月的EARN交易重新评估等级
     *
     * @param memberId 会员ID
     */
    void recalculateLevel(Long memberId);
}
