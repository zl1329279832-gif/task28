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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private RLock rLock;

    private Benefit benefit;
    private PointsAccount account;
    private MemberLevel memberLevel;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(rLock.isHeldByCurrentThread()).thenReturn(true);

        // Mock TransactionTemplate to execute callbacks directly
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

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

    // ======================== redeem tests ========================

    @Test
    void testRedeem_Success() {
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
        account.setAvailablePoints(100L);

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
    void testRedeem_StockExhausted_RollsBack() {
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
        when(benefitMapper.decrementStock(1L)).thenReturn(0);

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
        when(flowMapper.countDailyExchange(1001L, 1L)).thenReturn(1);

        assertThrows(BusinessException.class, () -> benefitService.redeem(request));
    }

    @Test
    void testRedeem_Duplicate_ReturnsExistingRecord() {
        RedeemRequest request = new RedeemRequest();
        request.setMemberId(1001L);
        request.setBenefitId(1L);
        request.setEventId("redeem-dup-001");
        request.setBizOrderNo("BIZ-001");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);

        PointsFlow existingFlow = new PointsFlow();
        existingFlow.setEventId("redeem-dup-001");
        when(flowService.checkIdempotent("redeem-dup-001")).thenReturn(existingFlow);

        ExchangeRecord existingRecord = new ExchangeRecord();
        existingRecord.setId(99L);
        existingRecord.setMemberId(1001L);
        existingRecord.setBenefitId(1L);
        existingRecord.setStatus(1);
        when(exchangeRecordMapper.selectOne(any())).thenReturn(existingRecord);

        ExchangeRecord result = benefitService.redeem(request);

        assertNotNull(result);
        assertEquals(99L, result.getId());
        // No deduction or stock change
        verify(accountMapper, never()).deductPoints(anyLong(), anyLong());
        verify(benefitMapper, never()).decrementStock(anyLong());
    }

    @Test
    void testRedeem_DoubleCheckIdempotent_InsideLock() {
        RedeemRequest request = new RedeemRequest();
        request.setMemberId(1001L);
        request.setBenefitId(1L);
        request.setEventId("redeem-dup-002");
        request.setBizOrderNo("BIZ-002");

        when(blacklistService.isBlacklisted(1001L)).thenReturn(false);

        PointsFlow existingFlow = new PointsFlow();
        existingFlow.setEventId("redeem-dup-002");
        // First null (outside lock), then existing (inside lock in transaction)
        when(flowService.checkIdempotent("redeem-dup-002")).thenReturn(null).thenReturn(existingFlow);

        ExchangeRecord existingRecord = new ExchangeRecord();
        existingRecord.setId(100L);
        when(exchangeRecordMapper.selectOne(any())).thenReturn(existingRecord);

        ExchangeRecord result = benefitService.redeem(request);

        assertNotNull(result);
        verify(accountMapper, never()).deductPoints(anyLong(), anyLong());
        verify(flowService, times(2)).checkIdempotent("redeem-dup-002");
    }

    // ======================== refundExchange tests ========================

    @Test
    void testRefundExchange_Success_UsesRefundPoints() {
        ExchangeRecord record = new ExchangeRecord();
        record.setId(1L);
        record.setMemberId(1001L);
        record.setBenefitId(1L);
        record.setPointsCost(500L);
        record.setStatus(1);
        record.setBizOrderNo("BIZ-001");
        when(exchangeRecordMapper.selectOne(any())).thenReturn(record);
        when(exchangeRecordMapper.selectById(1L)).thenReturn(record);
        when(flowService.checkIdempotent("refund-ex-001")).thenReturn(null);

        PointsAccount acc = new PointsAccount();
        acc.setMemberId(1001L);
        acc.setAvailablePoints(200L);
        when(accountService.getAccount(1001L)).thenReturn(acc);

        benefitService.refundExchange("BIZ-001", "refund-ex-001", "admin");

        // Verify refundPoints is used (not addPoints)
        verify(accountMapper).refundPoints(1001L, 500L);
        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        verify(benefitMapper).incrementStock(1L);
        verify(exchangeRecordMapper).updateById(argThat(r -> r.getStatus() == 3));
    }

    @Test
    void testRefundExchange_AlreadyRefunded_Skips() {
        ExchangeRecord record = new ExchangeRecord();
        record.setId(1L);
        record.setMemberId(1001L);
        record.setPointsCost(500L);
        record.setStatus(3); // Already refunded
        record.setBizOrderNo("BIZ-002");
        when(exchangeRecordMapper.selectOne(any())).thenReturn(record);

        benefitService.refundExchange("BIZ-002", "refund-ex-002", "admin");

        verify(accountMapper, never()).refundPoints(anyLong(), anyLong());
        verify(benefitMapper, never()).incrementStock(anyLong());
    }

    @Test
    void testRefundExchange_Duplicate_Skips() {
        PointsFlow existing = new PointsFlow();
        existing.setEventId("refund-ex-dup");
        when(flowService.checkIdempotent("refund-ex-dup")).thenReturn(existing);

        benefitService.refundExchange("BIZ-003", "refund-ex-dup", "admin");

        verify(accountMapper, never()).refundPoints(anyLong(), anyLong());
    }

    @Test
    void testRefundExchange_NotFound_ThrowsException() {
        when(flowService.checkIdempotent("refund-ex-003")).thenReturn(null);
        when(exchangeRecordMapper.selectOne(any())).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> benefitService.refundExchange("BIZ-NONE", "refund-ex-003", "admin"));
    }

    @Test
    void testRefundExchange_AcquiresLock() {
        ExchangeRecord record = new ExchangeRecord();
        record.setId(1L);
        record.setMemberId(1001L);
        record.setBenefitId(1L);
        record.setPointsCost(500L);
        record.setStatus(1);
        when(exchangeRecordMapper.selectOne(any())).thenReturn(record);
        when(exchangeRecordMapper.selectById(1L)).thenReturn(record);
        when(flowService.checkIdempotent("refund-ex-004")).thenReturn(null);

        PointsAccount acc = new PointsAccount();
        acc.setMemberId(1001L);
        acc.setAvailablePoints(200L);
        when(accountService.getAccount(1001L)).thenReturn(acc);

        benefitService.refundExchange("BIZ-004", "refund-ex-004", "admin");

        // Verify lock was acquired on the member
        verify(redissonClient).getLock("lock:benefit:redeem:1001");
    }
}
