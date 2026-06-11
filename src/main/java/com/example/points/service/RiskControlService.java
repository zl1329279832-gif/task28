package com.example.points.service;

import java.util.List;

public interface RiskControlService {

    boolean isCircuitBreakerAllowing(Long budgetPoolId);

    void evaluatePostIssuance(Long memberId, Long budgetPoolId, Long flowId, long points);

    List<String> evaluateInTransaction(Long memberId, Long budgetPoolId, Long flowId, long points);

    void triggerFreezeForFlow(Long memberId, Long budgetPoolId, Long flowId, long points, String freezeType);
}
