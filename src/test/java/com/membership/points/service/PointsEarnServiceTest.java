package com.membership.points.service;

import com.membership.points.common.exception.BlacklistedException;
import com.membership.points.common.result.Result;
import com.membership.points.common.util.RedisLockUtil;
import com.membership.points.dto.request.EarnPointsRequest;
import com.membership.points.entity.PointsAccount;
import com.membership.points.entity.PointsRule;
import com.membership.points.entity.PointsRuleVersion;
import com.membership.points.mapper.MemberMapper;
import com.membership.points.mapper.PointsBatchMapper;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.service.impl.PointsEarnServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointsEarnServiceTest {

    @Mock
    private IdempotentService idempotentService;

    @Mock
    private BlacklistService blacklistService;

    @Mock
    private PointsRuleService pointsRuleService;

    @Mock
    private PointsAccountService pointsAccountService;

    @Mock
    private PointsTransactionMapper pointsTransactionMapper;

    @Mock
    private PointsBatchMapper pointsBatchMapper;

    @Mock
    private MemberMapper memberMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CacheService cacheService;

    @Mock
    private RedisLockUtil redisLockUtil;

    @Mock
    private MemberLevelService memberLevelService;

    @InjectMocks
    private PointsEarnServiceImpl pointsEarnService;

    private EarnPointsRequest request;
    private PointsRule rule;
    private PointsRuleVersion version;
    private PointsAccount account;

    @BeforeEach
    void setUp() {
        request = new EarnPointsRequest();
        request.setMemberId(1L);
        request.setSource("CHECKIN");
        request.setIdempotentKey("test-key-001");
        request.setRemark("签到奖励");

        rule = new PointsRule();
        rule.setId(1L);
        rule.setSource("CHECKIN");
        rule.setBasePoints(10);
        rule.setMultiplier(BigDecimal.ONE);
        rule.setEnabled(1);

        version = new PointsRuleVersion();
        version.setId(1L);
        version.setRuleId(1L);
        version.setBasePoints(10);
        version.setMultiplier(BigDecimal.ONE);
        version.setExpireMonths(12);

        account = new PointsAccount();
        account.setId(1L);
        account.setMemberId(1L);
        account.setAvailablePoints(100L);
        account.setFrozenPoints(0L);
        account.setTotalEarned(100L);
        account.setTotalSpent(0L);
        account.setTotalExpired(0L);
        account.setVersion(1);
    }

    @Test
    void testEarnPoints_success() {
        // Arrange
        when(idempotentService.checkAndMark("test-key-001", "EARN")).thenReturn(true);
        when(blacklistService.isBlockedForEarn(1L)).thenReturn(false);
        when(pointsRuleService.getRuleBySource("CHECKIN")).thenReturn(rule);
        // base=10, multiplier=1.0, level multiplier=1.5 => 15 points
        when(pointsRuleService.calculatePoints(eq("CHECKIN"), isNull(), eq(1L))).thenReturn(15L);
        when(pointsRuleService.getCurrentVersion(1L)).thenReturn(version);
        when(pointsAccountService.getOrCreateAccount(1L)).thenReturn(account);
        when(pointsTransactionMapper.insert(any())).thenReturn(1);
        when(pointsBatchMapper.insert(any())).thenReturn(1);
        when(memberMapper.update(isNull(), any())).thenReturn(1);

        // Act
        Result<?> result = pointsEarnService.earnPoints(request);

        // Assert
        assertNotNull(result);
        assertEquals(200, result.getCode());
        verify(pointsAccountService).addPoints(eq(1L), eq(15L), eq(1));
        verify(idempotentService).markResult(eq("test-key-001"), eq("SUCCESS"));
        verify(cacheService).evictPointsAccount(1L);
        verify(auditLogService).log(eq("EARN"), eq("POINTS"), eq(1L), eq(1L), eq("SYSTEM"), anyString());
    }

    @Test
    void testEarnPoints_duplicateRequest() {
        // Arrange
        when(idempotentService.checkAndMark("test-key-001", "EARN")).thenReturn(false);
        when(idempotentService.getCachedResult("test-key-001")).thenReturn("cached-result");

        // Act
        Result<?> result = pointsEarnService.earnPoints(request);

        // Assert
        assertNotNull(result);
        assertEquals(200, result.getCode());
        // Verify no business logic was invoked
        verify(blacklistService, never()).isBlockedForEarn(anyLong());
        verify(pointsRuleService, never()).getRuleBySource(anyString());
        verify(pointsAccountService, never()).addPoints(anyLong(), anyLong(), anyInt());
    }

    @Test
    void testEarnPoints_blacklisted() {
        // Arrange
        when(idempotentService.checkAndMark("test-key-001", "EARN")).thenReturn(true);
        when(blacklistService.isBlockedForEarn(1L)).thenReturn(true);

        // Act & Assert
        assertThrows(BlacklistedException.class, () -> pointsEarnService.earnPoints(request));

        // Verify no further business logic was invoked
        verify(pointsRuleService, never()).getRuleBySource(anyString());
        verify(pointsAccountService, never()).addPoints(anyLong(), anyLong(), anyInt());
    }

    @Test
    void testEarnPoints_monthlyCap() {
        // Arrange: monthlyCap=100, already earned 95 this month, calculated=10 => clamped to 5
        rule.setMonthlyCap(100);
        when(idempotentService.checkAndMark("test-key-001", "EARN")).thenReturn(true);
        when(blacklistService.isBlockedForEarn(1L)).thenReturn(false);
        when(pointsRuleService.getRuleBySource("CHECKIN")).thenReturn(rule);
        when(pointsRuleService.calculatePoints(eq("CHECKIN"), isNull(), eq(1L))).thenReturn(10L);
        when(pointsTransactionMapper.sumEarnedPointsInMonth(eq(1L), eq("CHECKIN"), any(LocalDateTime.class)))
                .thenReturn(95L);
        when(pointsRuleService.getCurrentVersion(1L)).thenReturn(version);
        when(pointsAccountService.getOrCreateAccount(1L)).thenReturn(account);
        when(pointsTransactionMapper.insert(any())).thenReturn(1);
        when(pointsBatchMapper.insert(any())).thenReturn(1);
        when(memberMapper.update(isNull(), any())).thenReturn(1);

        // Act
        Result<?> result = pointsEarnService.earnPoints(request);

        // Assert
        assertNotNull(result);
        assertEquals(200, result.getCode());
        // Points should be clamped: 100 - 95 = 5
        verify(pointsAccountService).addPoints(eq(1L), eq(5L), eq(1));
    }
}
