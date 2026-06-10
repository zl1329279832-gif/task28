package com.membership.points.service;

import com.membership.points.entity.PointsRule;
import com.membership.points.entity.PointsRuleVersion;

import java.math.BigDecimal;

/**
 * 积分规则服务接口
 */
public interface PointsRuleService {

    /**
     * 根据来源获取积分规则
     *
     * @param source 积分来源
     * @return 积分规则
     */
    PointsRule getRuleBySource(String source);

    /**
     * 获取规则的当前生效版本
     *
     * @param ruleId 规则ID
     * @return 当前生效的规则版本
     */
    PointsRuleVersion getCurrentVersion(Long ruleId);

    /**
     * 计算应获得的积分
     *
     * @param source      积分来源
     * @param orderAmount 订单金额（消费获取时使用）
     * @param memberId    会员ID
     * @return 计算得到的积分数量
     */
    long calculatePoints(String source, BigDecimal orderAmount, Long memberId);

    /**
     * 创建新的规则版本
     * 关闭当前版本，创建新版本
     *
     * @param ruleId      规则ID
     * @param updatedRule 更新后的规则
     * @return 新创建的规则版本
     */
    PointsRuleVersion createNewVersion(Long ruleId, PointsRule updatedRule);
}
