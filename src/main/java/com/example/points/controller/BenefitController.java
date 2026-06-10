package com.example.points.controller;

import com.example.points.common.ApiResponse;
import com.example.points.dto.RedeemRequest;
import com.example.points.dto.RefundRequest;
import com.example.points.entity.Benefit;
import com.example.points.entity.ExchangeRecord;
import com.example.points.service.BenefitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/benefit")
@RequiredArgsConstructor
public class BenefitController {

    private final BenefitService benefitService;

    @GetMapping("/list")
    public ApiResponse<List<Benefit>> listActiveBenefits(@RequestParam Long memberId) {
        List<Benefit> list = benefitService.listActiveBenefits(memberId);
        return ApiResponse.ok(list);
    }

    @PostMapping("/redeem")
    public ApiResponse<ExchangeRecord> redeem(@Valid @RequestBody RedeemRequest request) {
        ExchangeRecord record = benefitService.redeem(request);
        return ApiResponse.ok(record);
    }

    @PostMapping("/refund")
    public ApiResponse<Void> refundExchange(
            @RequestParam String bizOrderNo,
            @RequestParam String eventId,
            @RequestParam String operator) {
        benefitService.refundExchange(bizOrderNo, eventId, operator);
        return ApiResponse.ok();
    }
}
