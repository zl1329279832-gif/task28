package com.example.points.service;

import com.example.points.entity.RiskEvent;

import java.util.List;

public interface RiskControlService {

    RiskEvent checkBeforeEarn(Long memberId, Long points, Long poolId);

    RiskEvent checkBeforeRefund(Long memberId, String bizOrderNo);

    boolean detectHighFrequency(Long memberId, int windowMinutes, int maxCount);

    boolean detectAbnormalRefund(Long memberId, int windowHours, int maxCount);

    RiskEvent triggerRiskEvent(Long memberId, String riskType,
                               String riskDetail, String relatedFlowIds, Long poolId);

    List<RiskEvent> listRiskEvents(Long memberId, String riskType, Integer status);
}
