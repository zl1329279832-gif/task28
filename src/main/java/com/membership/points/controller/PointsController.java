package com.membership.points.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.membership.points.common.result.Result;
import com.membership.points.dto.request.AdjustPointsRequest;
import com.membership.points.dto.request.EarnPointsRequest;
import com.membership.points.dto.request.FreezePointsRequest;
import com.membership.points.dto.request.TransactionQueryRequest;
import com.membership.points.dto.request.UnfreezePointsRequest;
import com.membership.points.dto.response.PageResponse;
import com.membership.points.dto.response.PointsAccountResponse;
import com.membership.points.dto.response.PointsTransactionResponse;
import com.membership.points.entity.PointsTransaction;
import com.membership.points.mapper.PointsTransactionMapper;
import com.membership.points.service.PointsAccountService;
import com.membership.points.service.PointsAdjustService;
import com.membership.points.service.PointsEarnService;
import com.membership.points.service.PointsFreezeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 积分操作控制器
 */
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
@Slf4j
public class PointsController {

    private final PointsEarnService pointsEarnService;
    private final PointsAdjustService pointsAdjustService;
    private final PointsFreezeService pointsFreezeService;
    private final PointsAccountService pointsAccountService;
    private final PointsTransactionMapper pointsTransactionMapper;

    @PostMapping("/earn")
    public Result<?> earnPoints(@Valid @RequestBody EarnPointsRequest request) {
        return pointsEarnService.earnPoints(request);
    }

    @PostMapping("/adjust")
    public Result<?> adjustPoints(@Valid @RequestBody AdjustPointsRequest request) {
        return pointsAdjustService.adjustPoints(request);
    }

    @PostMapping("/freeze")
    public Result<String> freezePoints(@Valid @RequestBody FreezePointsRequest request) {
        return pointsFreezeService.freezePoints(request);
    }

    @PostMapping("/unfreeze")
    public Result<?> unfreezePoints(@Valid @RequestBody UnfreezePointsRequest request) {
        return pointsFreezeService.unfreezePoints(request);
    }

    @GetMapping("/account/{memberId}")
    public Result<PointsAccountResponse> getAccount(@PathVariable Long memberId) {
        PointsAccountResponse response = pointsAccountService.getAccountResponse(memberId);
        return Result.ok(response);
    }

    @GetMapping("/transactions")
    public Result<PageResponse<PointsTransactionResponse>> getTransactions(TransactionQueryRequest request) {
        Page<PointsTransaction> page = new Page<>(request.getPageNum(), request.getPageSize());

        LambdaQueryWrapper<PointsTransaction> wrapper = new LambdaQueryWrapper<>();
        if (request.getMemberId() != null) {
            wrapper.eq(PointsTransaction::getMemberId, request.getMemberId());
        }
        if (request.getTransactionType() != null) {
            wrapper.eq(PointsTransaction::getTransactionType, request.getTransactionType());
        }
        if (request.getSource() != null) {
            wrapper.eq(PointsTransaction::getSource, request.getSource());
        }
        if (request.getStartTime() != null) {
            wrapper.ge(PointsTransaction::getCreatedAt, request.getStartTime());
        }
        if (request.getEndTime() != null) {
            wrapper.le(PointsTransaction::getCreatedAt, request.getEndTime());
        }
        wrapper.orderByDesc(PointsTransaction::getCreatedAt);

        Page<PointsTransaction> resultPage = pointsTransactionMapper.selectPage(page, wrapper);

        List<PointsTransactionResponse> records = resultPage.getRecords().stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        PageResponse<PointsTransactionResponse> pageResponse = new PageResponse<>();
        pageResponse.setRecords(records);
        pageResponse.setTotal(resultPage.getTotal());
        pageResponse.setPageNum((int) resultPage.getCurrent());
        pageResponse.setPageSize((int) resultPage.getSize());
        pageResponse.setTotalPages((int) Math.ceil((double) resultPage.getTotal() / resultPage.getSize()));

        return Result.ok(pageResponse);
    }

    private PointsTransactionResponse convertToResponse(PointsTransaction transaction) {
        PointsTransactionResponse response = new PointsTransactionResponse();
        response.setTransactionNo(transaction.getTransactionNo());
        response.setTransactionType(transaction.getTransactionType());
        response.setSource(transaction.getSource());
        response.setPointsAmount(transaction.getPointsAmount());
        response.setBalanceBefore(transaction.getBalanceBefore());
        response.setBalanceAfter(transaction.getBalanceAfter());
        response.setReferenceId(transaction.getReferenceId());
        response.setRemark(transaction.getRemark());
        response.setOperator(transaction.getOperator());
        response.setCreatedAt(transaction.getCreatedAt());
        return response;
    }
}
