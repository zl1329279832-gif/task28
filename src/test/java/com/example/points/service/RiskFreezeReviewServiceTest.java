package com.example.points.service;

import com.example.points.common.BusinessException;
import com.example.points.dto.ReviewDecisionRequest;
import com.example.points.entity.PointsFreeze;
import com.example.points.entity.RiskFreezeOrder;
import com.example.points.enums.ReviewStatus;
import com.example.points.mapper.PointsFreezeMapper;
import com.example.points.mapper.RiskEventMapper;
import com.example.points.mapper.RiskFreezeOrderMapper;
import com.example.points.service.impl.RiskFreezeReviewServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskFreezeReviewServiceTest {

    @InjectMocks
    private RiskFreezeReviewServiceImpl riskFreezeReviewService;

    @Mock private RiskFreezeOrderMapper riskFreezeOrderMapper;
    @Mock private RiskEventMapper riskEventMapper;
    @Mock private PointsFreezeMapper pointsFreezeMapper;
    @Mock private PointsFreezeService pointsFreezeService;
    @Mock private AuditLogService auditLogService;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock rLock;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
    }

    @Test
    void testApprove_Success() {
        RiskFreezeOrder order = new RiskFreezeOrder();
        order.setId(1L);
        order.setPointsFreezeId(100L);
        order.setReviewStatus(ReviewStatus.PENDING.getCode());

        PointsFreeze freeze = new PointsFreeze();
        freeze.setId(100L);
        freeze.setFreezeNo("RISK_123");

        when(riskFreezeOrderMapper.selectById(1L)).thenReturn(order);
        when(pointsFreezeMapper.selectById(100L)).thenReturn(freeze);
        when(riskFreezeOrderMapper.updateById(any())).thenReturn(1);

        ReviewDecisionRequest request = new ReviewDecisionRequest();
        request.setReviewer("admin");
        request.setRemark("审核通过");

        riskFreezeReviewService.approve(1L, request);

        verify(pointsFreezeService).unfreeze("RISK_123");
        assertEquals(ReviewStatus.APPROVED.getCode(), order.getReviewStatus());
        assertEquals("admin", order.getReviewer());
    }

    @Test
    void testApprove_AlreadyApproved_Throws() {
        RiskFreezeOrder order = new RiskFreezeOrder();
        order.setId(1L);
        order.setReviewStatus(ReviewStatus.APPROVED.getCode());

        when(riskFreezeOrderMapper.selectById(1L)).thenReturn(order);

        ReviewDecisionRequest request = new ReviewDecisionRequest();
        request.setReviewer("admin");

        assertThrows(BusinessException.class, () -> riskFreezeReviewService.approve(1L, request));
    }

    @Test
    void testReject_Success() {
        RiskFreezeOrder order = new RiskFreezeOrder();
        order.setId(1L);
        order.setPointsFreezeId(100L);
        order.setReviewStatus(ReviewStatus.PENDING.getCode());

        PointsFreeze freeze = new PointsFreeze();
        freeze.setId(100L);
        freeze.setFreezeNo("RISK_123");

        when(riskFreezeOrderMapper.selectById(1L)).thenReturn(order);
        when(pointsFreezeMapper.selectById(100L)).thenReturn(freeze);
        when(riskFreezeOrderMapper.updateById(any())).thenReturn(1);

        ReviewDecisionRequest request = new ReviewDecisionRequest();
        request.setReviewer("admin");
        request.setRemark("审核拒绝");

        riskFreezeReviewService.reject(1L, request);

        verify(pointsFreezeService).settleFreeze("RISK_123");
        assertEquals(ReviewStatus.REJECTED.getCode(), order.getReviewStatus());
    }

    @Test
    void testReject_AlreadyRejected_Throws() {
        RiskFreezeOrder order = new RiskFreezeOrder();
        order.setId(1L);
        order.setReviewStatus(ReviewStatus.REJECTED.getCode());

        when(riskFreezeOrderMapper.selectById(1L)).thenReturn(order);

        ReviewDecisionRequest request = new ReviewDecisionRequest();
        request.setReviewer("admin");

        assertThrows(BusinessException.class, () -> riskFreezeReviewService.reject(1L, request));
    }

    @Test
    void testApprove_LockNotAcquired_Throws() throws Exception {
        when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);

        ReviewDecisionRequest request = new ReviewDecisionRequest();
        request.setReviewer("admin");

        assertThrows(BusinessException.class, () -> riskFreezeReviewService.approve(1L, request));
    }

    @Test
    void testListPendingReviews() {
        RiskFreezeOrder order = new RiskFreezeOrder();
        order.setId(1L);
        order.setReviewStatus(ReviewStatus.PENDING.getCode());

        when(riskFreezeOrderMapper.selectList(any())).thenReturn(List.of(order));

        List<RiskFreezeOrder> result = riskFreezeReviewService.listPendingReviews();
        assertEquals(1, result.size());
    }

    @Test
    void testGetReview_NotFound_Throws() {
        when(riskFreezeOrderMapper.selectById(999L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> riskFreezeReviewService.getReview(999L));
    }
}
