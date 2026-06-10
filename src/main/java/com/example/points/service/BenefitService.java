package com.example.points.service;

import com.example.points.dto.RedeemRequest;
import com.example.points.entity.Benefit;
import com.example.points.entity.ExchangeRecord;

import java.util.List;

public interface BenefitService {

    List<Benefit> listActiveBenefits(Long memberId);

    ExchangeRecord redeem(RedeemRequest request);

    void refundExchange(String bizOrderNo, String eventId, String operator);
}
