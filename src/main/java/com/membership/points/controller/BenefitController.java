package com.membership.points.controller;

import com.membership.points.common.result.Result;
import com.membership.points.dto.request.CreateBenefitRequest;
import com.membership.points.dto.response.BenefitResponse;
import com.membership.points.dto.response.PageResponse;
import com.membership.points.entity.Benefit;
import com.membership.points.service.BenefitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 权益管理控制器
 */
@RestController
@RequestMapping("/api/benefits")
@RequiredArgsConstructor
public class BenefitController {

    private final BenefitService benefitService;

    @PostMapping
    public Result<BenefitResponse> createBenefit(@Valid @RequestBody CreateBenefitRequest request) {
        Benefit benefit = benefitService.createBenefit(request);
        return Result.ok(convertToResponse(benefit));
    }

    @GetMapping
    public Result<PageResponse<BenefitResponse>> listBenefits(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        PageResponse<BenefitResponse> response = benefitService.listBenefits(pageNum, pageSize);
        return Result.ok(response);
    }

    @GetMapping("/{benefitId}")
    public Result<BenefitResponse> getBenefit(@PathVariable Long benefitId) {
        Benefit benefit = benefitService.getBenefitById(benefitId);
        return Result.ok(convertToResponse(benefit));
    }

    private BenefitResponse convertToResponse(Benefit benefit) {
        BenefitResponse response = new BenefitResponse();
        response.setId(benefit.getId());
        response.setBenefitCode(benefit.getBenefitCode());
        response.setBenefitName(benefit.getBenefitName());
        response.setDescription(benefit.getDescription());
        response.setCategory(benefit.getCategory());
        response.setPointsCost(benefit.getPointsCost());
        response.setMinLevel(benefit.getMinLevel());
        response.setImageUrl(benefit.getImageUrl());
        response.setStartTime(benefit.getStartTime());
        response.setEndTime(benefit.getEndTime());
        return response;
    }
}
