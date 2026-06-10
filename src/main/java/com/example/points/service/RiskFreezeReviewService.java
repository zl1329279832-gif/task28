package com.example.points.service;

import com.example.points.dto.ReviewDecisionRequest;
import com.example.points.entity.RiskFreezeOrder;

import java.util.List;

public interface RiskFreezeReviewService {

    RiskFreezeOrder createRiskFreeze(Long memberId, Long poolId, Long riskEventId,
                                      String freezeType, Long points, int expireHours);

    void approve(Long freezeOrderId, ReviewDecisionRequest request);

    void reject(Long freezeOrderId, ReviewDecisionRequest request);

    List<RiskFreezeOrder> listPendingReviews();

    RiskFreezeOrder getReview(Long freezeOrderId);
}
