package com.example.points.service;

public interface RiskControlService {

    boolean isCircuitBreakerAllowing(Long budgetPoolId);

    void evaluatePostIssuance(Long memberId, Long budgetPoolId, Long flowId, long points);
}
