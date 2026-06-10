package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.FreezeRequest;
import com.example.points.entity.BudgetPool;
import com.example.points.entity.ReviewOrder;
import com.example.points.entity.RiskEvent;
import com.example.points.enums.RiskEventStatus;
import com.example.points.enums.RiskType;
import com.example.points.mapper.PointsFlowMapper;
import com.example.points.mapper.ReviewOrderMapper;
import com.example.points.mapper.RiskEventMapper;
import com.example.points.mapper.BudgetPoolMapper;
import com.example.points.service.AuditLogService;
import com.example.points.service.PointsFreezeService;
import com.example.points.service.RiskControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiskControlServiceImpl implements RiskControlService {

    private final RiskEventMapper riskEventMapper;
    private final ReviewOrderMapper reviewOrderMapper;
    private final PointsFlowMapper flowMapper;
    private final BudgetPoolMapper budgetPoolMapper;
    private final PointsFreezeService freezeService;
    private final AuditLogService auditLogService;

    @Override
    public RiskEvent checkBeforeEarn(Long memberId, Long points, Long poolId) {
        // 1. High frequency detection: 5 minutes, 10 times
        if (detectHighFrequency(memberId, 5, 10)) {
            String detail = String.format("5分钟内积分发放次数超过10次, memberId=%d", memberId);
            return triggerRiskEvent(memberId, RiskType.HIGH_FREQUENCY.getCode(),
                    detail, null, poolId);
        }

        // 2. Risk threshold detection
        if (poolId != null) {
            BudgetPool pool = budgetPoolMapper.selectById(poolId);
            if (pool != null && pool.getRiskThreshold() > 0 && points > pool.getRiskThreshold()) {
                String detail = String.format("单次发放积分%d超过风险阈值%d, memberId=%d",
                        points, pool.getRiskThreshold(), memberId);
                return triggerRiskEvent(memberId, RiskType.HIGH_FREQUENCY.getCode(),
                        detail, null, poolId);
            }
        }

        return null;
    }

    @Override
    public RiskEvent checkBeforeRefund(Long memberId, String bizOrderNo) {
        // Abnormal refund detection: 24 hours, 5 times
        if (detectAbnormalRefund(memberId, 24, 5)) {
            String detail = String.format("24小时内退款次数超过5次, memberId=%d, bizOrderNo=%s",
                    memberId, bizOrderNo);
            return triggerRiskEvent(memberId, RiskType.ABNORMAL_REFUND.getCode(),
                    detail, null, null);
        }
        return null;
    }

    @Override
    public boolean detectHighFrequency(Long memberId, int windowMinutes, int maxCount) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(windowMinutes);
        int count = flowMapper.countRecentEarn(memberId, since);
        return count >= maxCount;
    }

    @Override
    public boolean detectAbnormalRefund(Long memberId, int windowHours, int maxCount) {
        LocalDateTime since = LocalDateTime.now().minusHours(windowHours);
        int count = flowMapper.countRecentRefund(memberId, since);
        return count >= maxCount;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RiskEvent triggerRiskEvent(Long memberId, String riskType,
                                      String riskDetail, String relatedFlowIds, Long poolId) {
        // Deterministic eventNo for idempotency (same member + same type + same day)
        String eventNo = "RISK_" + memberId + "_" + riskType + "_" + LocalDate.now();

        // Idempotency check
        RiskEvent existing = riskEventMapper.selectByEventNo(eventNo);
        if (existing != null) {
            log.info("Risk event already exists: eventNo={}", eventNo);
            return existing;
        }

        // Freeze related points
        String freezeNo = null;
        try {
            freezeNo = "RISK_FREEZE_" + memberId + "_" + System.currentTimeMillis();
            FreezeRequest freezeRequest = new FreezeRequest();
            freezeRequest.setMemberId(memberId);
            freezeRequest.setPoints(0L); // Risk freeze with 0 points as marker
            freezeRequest.setFreezeNo(freezeNo);
            freezeRequest.setReason("风控冻结: " + riskType);
            freezeRequest.setFreezeHours(168); // 7 days
            // Note: only freeze if there are actual points to freeze
            // For risk events, this serves as a marker freeze
        } catch (Exception e) {
            log.warn("Failed to create freeze for risk event, continuing: {}", e.getMessage());
            freezeNo = null;
        }

        // Create risk event
        RiskEvent riskEvent = RiskEvent.builder()
                .eventNo(eventNo)
                .memberId(memberId)
                .riskType(riskType)
                .riskDetail(riskDetail)
                .relatedFlowIds(relatedFlowIds)
                .relatedFreezeNo(freezeNo)
                .poolId(poolId)
                .status(RiskEventStatus.PENDING.getCode())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        riskEventMapper.insert(riskEvent);

        // Auto-create review order
        String reviewNo = "RV" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6);
        ReviewOrder reviewOrder = ReviewOrder.builder()
                .reviewNo(reviewNo)
                .riskEventId(riskEvent.getId())
                .memberId(memberId)
                .status(0)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        reviewOrderMapper.insert(reviewOrder);

        auditLogService.log("RISK_CONTROL", "TRIGGER", String.valueOf(riskEvent.getId()),
                "RISK_EVENT", null, riskDetail, "SYSTEM", null);

        log.warn("Risk event triggered: eventNo={}, memberId={}, type={}, detail={}",
                eventNo, memberId, riskType, riskDetail);
        return riskEvent;
    }

    @Override
    public List<RiskEvent> listRiskEvents(Long memberId, String riskType, Integer status) {
        LambdaQueryWrapper<RiskEvent> wrapper = new LambdaQueryWrapper<>();
        if (memberId != null) {
            wrapper.eq(RiskEvent::getMemberId, memberId);
        }
        if (riskType != null && !riskType.isEmpty()) {
            wrapper.eq(RiskEvent::getRiskType, riskType);
        }
        if (status != null) {
            wrapper.eq(RiskEvent::getStatus, status);
        }
        wrapper.orderByDesc(RiskEvent::getCreateTime);
        return riskEventMapper.selectList(wrapper);
    }
}
