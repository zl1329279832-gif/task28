package com.example.points.service;

import com.example.points.dto.PointsEventRequest;
import com.example.points.entity.MemberLevel;
import com.example.points.entity.PointsAccount;
import com.example.points.entity.PointsRule;
import com.example.points.mapper.MemberLevelMapper;
import com.example.points.mapper.PointsFlowMapper;
import com.example.points.mapper.PointsRuleMapper;
import com.example.points.service.impl.RuleEngineImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleEngineTest {

    @InjectMocks
    private RuleEngineImpl ruleEngine;

    @Mock private PointsRuleMapper ruleMapper;
    @Mock private PointsFlowMapper flowMapper;
    @Mock private MemberLevelMapper memberLevelMapper;
    @Mock private RedissonClient redissonClient;
    @Mock private RBucket<List<PointsRule>> rBucket;

    private PointsAccount account;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        account = new PointsAccount();
        account.setMemberId(1001L);
        account.setAvailablePoints(500L);
        account.setMonthlyEarned(0L);
        account.setLevelId(1L);

        // Default: no cache - use doReturn to avoid generic type mismatch
        lenient().doReturn(rBucket).when(redissonClient).getBucket(anyString());
        lenient().when(rBucket.isExists()).thenReturn(false);
    }

    @Test
    void testCalculatePoints_Register() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventType("REGISTER");
        request.setMemberId(1001L);

        PointsRule registerRule = new PointsRule();
        registerRule.setRuleCode("REGISTER");
        registerRule.setRuleValue("{\"points\": 100}");
        registerRule.setStatus(1);
        registerRule.setVersion(1);

        PointsRule monthlyCap = new PointsRule();
        monthlyCap.setRuleCode("MONTHLY_CAP");
        monthlyCap.setRuleValue("{\"maxPoints\": 10000}");
        monthlyCap.setStatus(1);

        when(ruleMapper.selectList(any())).thenReturn(Arrays.asList(registerRule, monthlyCap));

        long result = ruleEngine.calculatePoints(request, account);
        assertEquals(100L, result);
    }

    @Test
    void testCalculatePoints_Purchase() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventType("PURCHASE");
        request.setMemberId(1001L);
        request.setAmount(200L); // 200 yuan

        PointsRule purchaseRule = new PointsRule();
        purchaseRule.setRuleCode("PURCHASE");
        purchaseRule.setRuleValue("{\"rate\": 1, \"unit\": \"YUAN\", \"minAmount\": 10}");
        purchaseRule.setStatus(1);

        PointsRule monthlyCap = new PointsRule();
        monthlyCap.setRuleCode("MONTHLY_CAP");
        monthlyCap.setRuleValue("{\"maxPoints\": 10000}");
        monthlyCap.setStatus(1);

        when(ruleMapper.selectList(any())).thenReturn(Arrays.asList(purchaseRule, monthlyCap));

        long result = ruleEngine.calculatePoints(request, account);
        assertEquals(200L, result);
    }

    @Test
    void testCalculatePoints_MonthlyCap() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventType("REGISTER");
        request.setMemberId(1001L);

        account.setMonthlyEarned(9950L); // Already near cap

        PointsRule registerRule = new PointsRule();
        registerRule.setRuleCode("REGISTER");
        registerRule.setRuleValue("{\"points\": 100}");
        registerRule.setStatus(1);

        PointsRule monthlyCap = new PointsRule();
        monthlyCap.setRuleCode("MONTHLY_CAP");
        monthlyCap.setRuleValue("{\"maxPoints\": 10000}");
        monthlyCap.setStatus(1);

        when(ruleMapper.selectList(any())).thenReturn(Arrays.asList(registerRule, monthlyCap));

        long result = ruleEngine.calculatePoints(request, account);
        assertEquals(50L, result); // Only 50 remaining under cap
    }

    @Test
    void testCalculatePoints_PurchaseBelowMinAmount() {
        PointsEventRequest request = new PointsEventRequest();
        request.setEventType("PURCHASE");
        request.setMemberId(1001L);
        request.setAmount(5L); // Below minAmount of 10

        PointsRule purchaseRule = new PointsRule();
        purchaseRule.setRuleCode("PURCHASE");
        purchaseRule.setRuleValue("{\"rate\": 1, \"unit\": \"YUAN\", \"minAmount\": 10}");
        purchaseRule.setStatus(1);

        PointsRule monthlyCap = new PointsRule();
        monthlyCap.setRuleCode("MONTHLY_CAP");
        monthlyCap.setRuleValue("{\"maxPoints\": 10000}");
        monthlyCap.setStatus(1);

        when(ruleMapper.selectList(any())).thenReturn(Arrays.asList(purchaseRule, monthlyCap));

        long result = ruleEngine.calculatePoints(request, account);
        assertEquals(0L, result); // Below minimum, no points
    }
}
