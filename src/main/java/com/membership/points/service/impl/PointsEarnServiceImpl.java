package com.membership.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.membership.points.common.exception.BlacklistedException;
import com.membership.points.common.exception.BusinessException;
import com.membership.points.common.enums.PointsSourceEnum;
import com.membership.points.common.enums.PointsTransactionTypeEnum;
import com.membership.points.common.result.Result;
import com.membership.points.common.util.RedisLockUtil;
import com.membership.points.common.util.SnowflakeIdGenerator;
import com.membership.points.dto.request.EarnPointsRequest;
import com.membership.points.entity.Member;
import com.membership.points.entity.PointsAccount;
import com.membership.points.entity.PointsBatch;
import com.membership.points.entity.PointsRule;
import com.membership.points.entity.PointsRuleVersion;
import com.membership.points.entity.PointsTransaction;
import com.membership.points.mapper.MemberMapper;
import com.membership.points.mapper.PointsBatchMapper;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.service.AuditLogService;
import com.membership.points.service.BlacklistService;
import com.membership.points.service.CacheService;
import com.membership.points.service.IdempotentService;
import com.membership.points.service.PointsAccountService;
import com.membership.points.service.MemberLevelService;
import com.membership.points.service.PointsEarnService;
import com.membership.points.service.PointsRuleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.CompletableFuture;

/**
 * 积分获取服务实现
 */
@Slf4j
@Service
public class PointsEarnServiceImpl implements PointsEarnService {

    private final IdempotentService idempotentService;
    private final BlacklistService blacklistService;
    private final PointsRuleService pointsRuleService;
    private final PointsAccountService pointsAccountService;
    private final PointsTransactionMapper pointsTransactionMapper;
    private final PointsBatchMapper pointsBatchMapper;
    private final MemberMapper memberMapper;
    private final AuditLogService auditLogService;
    private final CacheService cacheService;
    private final RedisLockUtil redisLockUtil;
    private final MemberLevelService memberLevelService;
    private final TransactionTemplate transactionTemplate;

    public PointsEarnServiceImpl(IdempotentService idempotentService,
                                 BlacklistService blacklistService,
                                 PointsRuleService pointsRuleService,
                                 PointsAccountService pointsAccountService,
                                 PointsTransactionMapper pointsTransactionMapper,
                                 PointsBatchMapper pointsBatchMapper,
                                 MemberMapper memberMapper,
                                 AuditLogService auditLogService,
                                 CacheService cacheService,
                                 RedisLockUtil redisLockUtil,
                                 MemberLevelService memberLevelService,
                                 TransactionTemplate transactionTemplate) {
        this.idempotentService = idempotentService;
        this.blacklistService = blacklistService;
        this.pointsRuleService = pointsRuleService;
        this.pointsAccountService = pointsAccountService;
        this.pointsTransactionMapper = pointsTransactionMapper;
        this.pointsBatchMapper = pointsBatchMapper;
        this.memberMapper = memberMapper;
        this.auditLogService = auditLogService;
        this.cacheService = cacheService;
        this.redisLockUtil = redisLockUtil;
        this.memberLevelService = memberLevelService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Result<?> earnPoints(EarnPointsRequest request) {
        // 1. 幂等检查
        boolean isNew = idempotentService.checkAndMark(request.getIdempotentKey(), "EARN");
        if (!isNew) {
            String cachedResult = idempotentService.getCachedResult(request.getIdempotentKey());
            return Result.ok(cachedResult);
        }

        Long memberId = request.getMemberId();
        String source = request.getSource();

        // 2. 黑名单检查
        if (blacklistService.isBlockedForEarn(memberId)) {
            throw new BlacklistedException("会员已被列入黑名单，无法获取积分");
        }

        // 3. 加载积分规则
        PointsRule rule = pointsRuleService.getRuleBySource(source);
        if (rule == null) {
            throw new BusinessException("积分规则不存在");
        }

        // 4. 计算积分
        long calculatedPoints = pointsRuleService.calculatePoints(source, request.getOrderAmount(), memberId);

        // 5. 月度上限检查
        if (rule.getMonthlyCap() != null) {
            LocalDateTime startOfMonth = LocalDateTime.of(LocalDate.now().withDayOfMonth(1), LocalTime.MIN);
            Long sum = pointsTransactionMapper.sumEarnedPointsInMonth(memberId, source, startOfMonth);
            long monthlyCapLong = rule.getMonthlyCap().longValue();
            if (sum + calculatedPoints > monthlyCapLong) {
                calculatedPoints = monthlyCapLong - sum;
            }
            if (calculatedPoints <= 0) {
                return Result.ok("已达本月积分上限");
            }
        }

        // 6. 获取当前规则版本
        PointsRuleVersion version = pointsRuleService.getCurrentVersion(rule.getId());

        // Capture as effectively final for lambda
        final long finalPoints = calculatedPoints;

        // 7. 执行事务
        transactionTemplate.executeWithoutResult(status -> {
            doEarnTransaction(memberId, source, finalPoints, version, request);
        });

        // 8. 事务后处理：清除缓存、异步升级检查、记录审计日志
        cacheService.evictPointsAccount(memberId);

        CompletableFuture.runAsync(() -> {
            try {
                memberLevelService.checkAndUpgrade(memberId);
            } catch (Exception e) {
                log.error("会员等级升级检查异常, memberId: {}", memberId, e);
            }
        });

        auditLogService.log("EARN", "POINTS", memberId, memberId, "SYSTEM",
                "积分获取: source=" + source + ", points=" + finalPoints);

        // 9. 标记幂等结果
        idempotentService.markResult(request.getIdempotentKey(), "SUCCESS");

        // 10. 返回成功
        return Result.ok();
    }

    private void doEarnTransaction(Long memberId, String source, long calculatedPoints,
                                   PointsRuleVersion version, EarnPointsRequest request) {
        // a. 加载账户
        PointsAccount account = pointsAccountService.getOrCreateAccount(memberId);

        // b. 构建积分交易记录
        PointsTransaction transaction = new PointsTransaction();
        transaction.setTransactionNo(SnowflakeIdGenerator.generateTransactionNo("E"));
        transaction.setMemberId(memberId);
        transaction.setTransactionType(PointsTransactionTypeEnum.EARN.getCode());
        transaction.setSource(source);
        transaction.setPointsAmount(calculatedPoints);
        transaction.setBalanceBefore(account.getAvailablePoints());
        transaction.setBalanceAfter(account.getAvailablePoints() + calculatedPoints);
        transaction.setFrozenBefore(account.getFrozenPoints());
        transaction.setFrozenAfter(account.getFrozenPoints());
        transaction.setRuleVersionId(version.getId());
        transaction.setReferenceId(request.getReferenceId());
        transaction.setRemark(request.getRemark());
        transaction.setCreatedAt(LocalDateTime.now());

        // c. 插入交易记录
        pointsTransactionMapper.insert(transaction);

        // d. 构建积分批次
        PointsBatch batch = new PointsBatch();
        batch.setMemberId(memberId);
        batch.setSource(source);
        batch.setOriginalPoints(calculatedPoints);
        batch.setRemainingPoints(calculatedPoints);
        batch.setFrozenPoints(0L);
        batch.setRuleVersionId(version.getId());
        batch.setEarnTransactionId(transaction.getId());
        batch.setEarnedAt(LocalDateTime.now());
        batch.setExpireAt(LocalDateTime.now().plusMonths(version.getExpireMonths()));
        batch.setExpired(0);
        batch.setCreatedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());

        // e. 插入批次
        pointsBatchMapper.insert(batch);

        // f. 增加账户积分
        pointsAccountService.addPoints(memberId, calculatedPoints, account.getVersion());

        // g. 更新会员累积积分
        LambdaUpdateWrapper<Member> memberUpdate = new LambdaUpdateWrapper<>();
        memberUpdate.eq(Member::getId, memberId)
                .setSql("accumulated_points = accumulated_points + " + calculatedPoints);
        memberMapper.update(null, memberUpdate);

        // h. 回填批次ID到交易记录
        transaction.setBatchId(batch.getId());
        pointsTransactionMapper.updateById(transaction);
    }
}
