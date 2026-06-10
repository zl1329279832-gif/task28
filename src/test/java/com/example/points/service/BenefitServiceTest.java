package com.example.points.service;

import com.example.points.common.BusinessException;
import com.example.points.dto.RedeemRequest;
import com.example.points.entity.*;
import com.example.points.mapper.*;
import com.example.points.service.impl.BenefitServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BenefitServiceTest {

    @InjectMocks
    private BenefitServiceImpl benefitService;

    @Mock private BenefitMapper benefitMapper;
    @Mock private ExchangeRecordMapper exchangeRecordMapper;
    @Mock private PointsAccountMapper accountMapper;
    @Mock private PointsFlowMapper flowMapper;
    @Mock private PointsFlowService flowService;
    @Mock private PointsAccountService accountService;
    @Mock private BlacklistService blacklistService;
    @Mock private AuditLogService auditLogService;
    @Mock private MemberLevelMapper memberLevelMapper;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock rLock;

    private Benefit benefit;
    private PointsAccount account;
    private MemberLevel memberLevel;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        benefit = new Benefit();
        benefit.setId(1L);
        benefit.setBenefitName("10元优惠券");
        benefit.setPointsCost(500L);
        benefit.setTotalStock(1000);
        benefit.setAvailableStock(100);
        benefit.setMinLevelId(1L);
        benefit.setDailyLimit(1);
        benefit.setTotalLimit(5);
        benefit.setStatus(1);
        benefit.setStartTime(LocalDateTime.now().minusDays(1));
        benefit.setEndTime(LocalDateTime.now().plusDays(30));

        account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(1000L);
        account.setLevelId(1L);
        account.setStatus(1);

        memberLevel = new MemberLevel();
        memberLevel.setId(1L);
        memberLevel.setLevelCode(1);
    }

    @Test
    void testRedeem_Success() throws Exception {
        RedeemRequest request = new RedeemRequest();
        request.setMemberId(1001L);
        request.setBenefitId(1L);
        request.setEventId("redeem-001");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(flowService.checkIdempotent("redeem-001")).thenReturn(null);
        when(benefitMapper.selectById(1L)).thenReturn(benefit);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(memberLevelMapper.selectById(1L)).thenReturn(memberLevel);
        when(flowMapper.countDailyExchange(1001L, 1L)).thenReturn(0);
        when(accountMapper.deductPoints(1001L, 500L)).thenReturn(1);
        when(benefitMapper.decrementStock(1L)).thenReturn(1);

        ExchangeRecord result = benefitService.redeem(request);

        assertNotNull(result);
        assertEquals(1L, result.getBenefitId());
        verify(accountMapper).deductPoints(1001L, 500L);
        verify(benefitMapper).decrementStock(1L);
        verify(exchangeRecordMapper).insert(any(ExchangeRecord.class));
    }

    @Test
    void testRedeem_InsufficientPoints() {
        account.setAvailablePoints(100L); // Not enough

        RedeemRequest request = new RedeemRequest();
        request.setMemberId(1001L);
        request.setBenefitId(1L);
        request.setEventId("redeem-002");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(flowService.checkIdempotent("redeem-002")).thenReturn(null);
        when(benefitMapper.selectById(1L)).thenReturn(benefit);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(memberLevelMapper.selectById(1L)).thenReturn(memberLevel);

        assertThrows(BusinessException.class, () -> benefitService.redeem(request));
        verify(accountMapper, never()).deductPoints(anyLong(), anyLong());
    }

    @Test
    void testRedeem_StockExhausted_RollsBack() throws Exception {
        RedeemRequest request = new RedeemRequest();
        request.setMemberId(1001L);
        request.setBenefitId(1L);
        request.setEventId("redeem-003");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(flowService.checkIdempotent("redeem-003")).thenReturn(null);
        when(benefitMapper.selectById(1L)).thenReturn(benefit);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(memberLevelMapper.selectById(1L)).thenReturn(memberLevel);
        when(flowMapper.countDailyExchange(1001L, 1L)).thenReturn(0);
        when(accountMapper.deductPoints(1001L, 500L)).thenReturn(1);
        when(benefitMapper.decrementStock(1L)).thenReturn(0); // Stock exhausted

        // Should throw and the @Transactional would rollback the points deduction
        assertThrows(BusinessException.class, () -> benefitService.redeem(request));
    }

    @Test
    void testRedeem_Blacklisted_ThrowsException() {
        RedeemRequest request = new RedeemRequest();
        request.setMemberId(1001L);
        request.setBenefitId(1L);
        request.setEventId("redeem-004");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(true);

        assertThrows(BusinessException.class, () -> benefitService.redeem(request));
    }

    @Test
    void testRedeem_DailyLimitExceeded() {
        RedeemRequest request = new RedeemRequest();
        request.setMemberId(1001L);
        request.setBenefitId(1L);
        request.setEventId("redeem-005");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);
        when(flowService.checkIdempotent("redeem-005")).thenReturn(null);
        when(benefitMapper.selectById(1L)).thenReturn(benefit);
        when(accountService.getAccount(1001L)).thenReturn(account);
        when(memberLevelMapper.selectById(1L)).thenReturn(memberLevel);
        when(flowMapper.countDailyExchange(1001L, 1L)).thenReturn(1); // Already hit daily limit

        assertThrows(BusinessException.class, () -> benefitService.redeem(request));
    }
}
