package com.example.points.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.points.common.BusinessException;
import com.example.points.dto.RedeemRequest;
import com.example.points.entity.*;
import com.example.points.mapper.*;
import com.example.points.service.impl.BenefitServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

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
    @Mock private BudgetPoolService budgetPoolService;
    @Mock private RiskControlService riskControlService;
    @Mock private TransactionTemplate transactionTemplate;

    private Benefit benefit;
    private PointsAccount account;
    private MemberLevel memberLevel;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

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

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(500L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        ExchangeRecord result = benefitService.redeem(request);

        assertNotNull(result);
        assertEquals(1L, result.getBenefitId());
        verify(accountMapper).deductPoints(1001L, 500L);
        verify(benefitMapper).decrementStock(1L);
        verify(exchangeRecordMapper).insert(any(ExchangeRecord.class));

        ArgumentCaptor<PointsFlow> flowCaptor = ArgumentCaptor.forClass(PointsFlow.class);
        verify(flowService).saveFlow(flowCaptor.capture());
        PointsFlow savedFlow = flowCaptor.getValue();
        assertEquals(1000L, savedFlow.getBeforePoints());
        assertEquals(500L, savedFlow.getAfterPoints());
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

    @Test
    void testRedeem_DuplicateEventId_ReturnsExistingRecord() {
        RedeemRequest request = new RedeemRequest();
        request.setMemberId(1001L);
        request.setBenefitId(1L);
        request.setEventId("redeem-dup");

        PointsFlow existingFlow = new PointsFlow();
        existingFlow.setEventId("redeem-dup");
        when(flowService.checkIdempotent("redeem-dup")).thenReturn(existingFlow);

        ExchangeRecord existingRecord = new ExchangeRecord();
        existingRecord.setMemberId(1001L);
        existingRecord.setBenefitId(1L);
        existingRecord.setStatus(1);
        when(exchangeRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingRecord);

        ExchangeRecord result = benefitService.redeem(request);
        assertNotNull(result);
        verify(accountMapper, never()).deductPoints(anyLong(), anyLong());
    }

    @Test
    void testRefundExchange_Success() throws Exception {
        ExchangeRecord record = ExchangeRecord.builder()
                .memberId(1001L)
                .benefitId(1L)
                .pointsCost(500L)
                .status(1) // COMPLETED
                .bizOrderNo("BIZ-001")
                .build();
        when(flowService.checkIdempotent("refund-ex-001")).thenReturn(null);
        when(exchangeRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);
        when(accountMapper.addPoints(1001L, 500L)).thenReturn(1);
        when(benefitMapper.incrementStock(1L)).thenReturn(1);

        PointsAccount updatedAccount = new PointsAccount();
        updatedAccount.setMemberId(1001L);
        updatedAccount.setAvailablePoints(1500L);
        when(accountMapper.selectByMemberId(1001L)).thenReturn(updatedAccount);

        benefitService.refundExchange("BIZ-001", "refund-ex-001", "admin");

        verify(accountMapper).addPoints(1001L, 500L);
        verify(benefitMapper).incrementStock(1L);
        verify(exchangeRecordMapper).updateById(any(ExchangeRecord.class));
        verify(flowService).saveFlow(any(PointsFlow.class));
    }

    @Test
    void testRefundExchange_NoExchangeRecord_ThrowsException() {
        when(flowService.checkIdempotent("refund-ex-002")).thenReturn(null);
        when(exchangeRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThrows(BusinessException.class, () ->
                benefitService.refundExchange("BIZ-NONE", "refund-ex-002", "admin"));
    }

    @Test
    void testRefundExchange_AlreadyRefunded_Noop() {
        ExchangeRecord record = ExchangeRecord.builder()
                .memberId(1001L)
                .benefitId(1L)
                .pointsCost(500L)
                .status(3) // REFUNDED
                .bizOrderNo("BIZ-002")
                .build();
        when(flowService.checkIdempotent("refund-ex-003")).thenReturn(null);
        when(exchangeRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);

        benefitService.refundExchange("BIZ-002", "refund-ex-003", "admin");

        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
        verify(benefitMapper, never()).incrementStock(anyLong());
    }

    @Test
    void testRefundExchange_IdempotentByEventId() {
        PointsFlow existingFlow = new PointsFlow();
        existingFlow.setEventId("refund-ex-004");
        when(flowService.checkIdempotent("refund-ex-004")).thenReturn(existingFlow);

        benefitService.refundExchange("BIZ-003", "refund-ex-004", "admin");

        verify(exchangeRecordMapper, never()).selectOne(any());
        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
    }

    @Test
    void testRefundExchange_LockNotAcquired_ThrowsException() throws Exception {
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        ExchangeRecord record = ExchangeRecord.builder()
                .memberId(1001L)
                .benefitId(1L)
                .pointsCost(500L)
                .status(1)
                .bizOrderNo("BIZ-004")
                .build();
        when(flowService.checkIdempotent("refund-ex-005")).thenReturn(null);
        when(exchangeRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);

        assertThrows(BusinessException.class, () ->
                benefitService.refundExchange("BIZ-004", "refund-ex-005", "admin"));
    }

    @Test
    void testRefundExchange_DoubleRefundPrevented() throws Exception {
        // First check: status=1, inside lock re-check: status=3 (another thread already refunded)
        ExchangeRecord recordFirst = ExchangeRecord.builder()
                .memberId(1001L)
                .benefitId(1L)
                .pointsCost(500L)
                .status(1)
                .bizOrderNo("BIZ-005")
                .build();
        ExchangeRecord recordSecond = ExchangeRecord.builder()
                .memberId(1001L)
                .benefitId(1L)
                .pointsCost(500L)
                .status(3) // Already refunded inside lock
                .bizOrderNo("BIZ-005")
                .build();
        when(flowService.checkIdempotent("refund-ex-006")).thenReturn(null);
        when(exchangeRecordMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(recordFirst)
                .thenReturn(recordSecond);

        benefitService.refundExchange("BIZ-005", "refund-ex-006", "admin");

        verify(accountMapper, never()).addPoints(anyLong(), anyLong());
    }
}
