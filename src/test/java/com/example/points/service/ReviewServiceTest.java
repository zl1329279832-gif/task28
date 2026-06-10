package com.example.points.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.ReviewRequest;
import com.example.points.entity.ReviewOrder;
import com.example.points.entity.RiskEvent;
import com.example.points.enums.ReviewResult;
import com.example.points.enums.RiskEventStatus;
import com.example.points.mapper.BudgetPoolMapper;
import com.example.points.mapper.ReviewOrderMapper;
import com.example.points.mapper.RiskEventMapper;
import com.example.points.service.impl.ReviewServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @InjectMocks
    private ReviewServiceImpl reviewService;

    @Mock private ReviewOrderMapper reviewOrderMapper;
    @Mock private RiskEventMapper riskEventMapper;
    @Mock private PointsFreezeService freezeService;
    @Mock private BlacklistService blacklistService;
    @Mock private BudgetPoolMapper budgetPoolMapper;
    @Mock private AuditLogService auditLogService;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock rLock;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
    }

    private ReviewOrder createPendingReview(Long riskEventId) {
        return ReviewOrder.builder()
                .id(1L)
                .reviewNo("RV-001")
                .riskEventId(riskEventId)
                .memberId(1001L)
                .status(0)
                .createTime(LocalDateTime.now())
                .build();
    }

    private RiskEvent createRiskEvent(Long id, String freezeNo, Long poolId) {
        return RiskEvent.builder()
                .id(id)
                .eventNo("RISK_1001_HIGH_FREQUENCY_2026-06-10")
                .memberId(1001L)
                .riskType("HIGH_FREQUENCY")
                .relatedFreezeNo(freezeNo)
                .poolId(poolId)
                .status(RiskEventStatus.PENDING.getCode())
                .build();
    }

    @Test
    void testReview_Approved_UnfreezesPoints() {
        ReviewOrder order = createPendingReview(1L);
        RiskEvent riskEvent = createRiskEvent(1L, "RISK_FREEZE_1001_123", null);

        when(reviewOrderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(riskEventMapper.selectById(1L)).thenReturn(riskEvent);

        ReviewRequest request = new ReviewRequest();
        request.setReviewNo("RV-001");
        request.setReviewResult(ReviewResult.APPROVED.getCode());
        request.setReviewer("admin");
        request.setReviewComment("通过");

        reviewService.review(request);

        verify(freezeService).unfreeze("RISK_FREEZE_1001_123");
        verify(riskEventMapper).updateById(argThat(e ->
                e.getStatus() == RiskEventStatus.APPROVED.getCode()));
        verify(reviewOrderMapper).updateById(argThat(o ->
                o.getStatus() == 1 && "UNFREEZE".equals(o.getActionTaken())));
    }

    @Test
    void testReview_Rejected_DeductsPoints() {
        ReviewOrder order = createPendingReview(1L);
        RiskEvent riskEvent = createRiskEvent(1L, "RISK_FREEZE_1001_123", null);

        when(reviewOrderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(riskEventMapper.selectById(1L)).thenReturn(riskEvent);

        ReviewRequest request = new ReviewRequest();
        request.setReviewNo("RV-001");
        request.setReviewResult(ReviewResult.REJECTED.getCode());
        request.setReviewer("admin");
        request.setAddBlacklist(false);

        reviewService.review(request);

        verify(freezeService).settleFreeze("RISK_FREEZE_1001_123");
        verify(riskEventMapper).updateById(argThat(e ->
                e.getStatus() == RiskEventStatus.REJECTED.getCode()));
        verify(reviewOrderMapper).updateById(argThat(o ->
                "DEDUCT".equals(o.getActionTaken())));
    }

    @Test
    void testReview_Rejected_WithBlacklist() {
        ReviewOrder order = createPendingReview(1L);
        RiskEvent riskEvent = createRiskEvent(1L, "RISK_FREEZE_1001_123", null);

        when(reviewOrderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(riskEventMapper.selectById(1L)).thenReturn(riskEvent);

        ReviewRequest request = new ReviewRequest();
        request.setReviewNo("RV-001");
        request.setReviewResult(ReviewResult.REJECTED.getCode());
        request.setReviewer("admin");
        request.setReviewComment("恶意刷分");
        request.setAddBlacklist(true);

        reviewService.review(request);

        verify(freezeService).settleFreeze("RISK_FREEZE_1001_123");
        verify(blacklistService).addToBlacklist(eq(1001L), contains("风控复核拒绝"), eq("admin"), isNull());
        verify(reviewOrderMapper).updateById(argThat(o ->
                "DEDUCT+ADD_BLACKLIST".equals(o.getActionTaken())));
    }

    @Test
    void testReview_AlreadyReviewed_ThrowsException() {
        ReviewOrder order = createPendingReview(1L);
        order.setStatus(1); // already reviewed

        when(reviewOrderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);

        ReviewRequest request = new ReviewRequest();
        request.setReviewNo("RV-001");
        request.setReviewResult(1);
        request.setReviewer("admin");

        assertThrows(BusinessException.class, () -> reviewService.review(request));
        verify(freezeService, never()).unfreeze(anyString());
    }

    @Test
    void testReview_ReviewNotFound_ThrowsException() {
        when(reviewOrderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ReviewRequest request = new ReviewRequest();
        request.setReviewNo("RV-NONE");
        request.setReviewResult(1);
        request.setReviewer("admin");

        assertThrows(BusinessException.class, () -> reviewService.review(request));
    }

    @Test
    void testReview_LockNotAcquired_ThrowsException() throws Exception {
        when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);

        ReviewRequest request = new ReviewRequest();
        request.setReviewNo("RV-001");
        request.setReviewResult(1);
        request.setReviewer("admin");

        assertThrows(BusinessException.class, () -> reviewService.review(request));
    }

    @Test
    void testReview_Approved_NoFreezeNo_SkipsUnfreeze() {
        ReviewOrder order = createPendingReview(1L);
        RiskEvent riskEvent = createRiskEvent(1L, null, null);

        when(reviewOrderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(riskEventMapper.selectById(1L)).thenReturn(riskEvent);

        ReviewRequest request = new ReviewRequest();
        request.setReviewNo("RV-001");
        request.setReviewResult(ReviewResult.APPROVED.getCode());
        request.setReviewer("admin");

        reviewService.review(request);

        verify(freezeService, never()).unfreeze(anyString());
        verify(reviewOrderMapper).updateById(argThat(o -> o.getStatus() == 1));
    }

    @Test
    void testGetReview_NotFound_ThrowsException() {
        when(reviewOrderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThrows(BusinessException.class, () -> reviewService.getReview("RV-NONE"));
    }

    @Test
    void testReview_Rejected_NoFreezeNo_SkipsSettle() {
        ReviewOrder order = createPendingReview(1L);
        RiskEvent riskEvent = createRiskEvent(1L, null, null);

        when(reviewOrderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(riskEventMapper.selectById(1L)).thenReturn(riskEvent);

        ReviewRequest request = new ReviewRequest();
        request.setReviewNo("RV-001");
        request.setReviewResult(ReviewResult.REJECTED.getCode());
        request.setReviewer("admin");
        request.setAddBlacklist(false);

        reviewService.review(request);

        verify(freezeService, never()).settleFreeze(anyString());
        verify(reviewOrderMapper).updateById(argThat(o -> o.getStatus() == 1));
    }
}
