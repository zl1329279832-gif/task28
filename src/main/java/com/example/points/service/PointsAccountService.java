package com.example.points.service;

import com.example.points.entity.PointsAccount;

public interface PointsAccountService {

    PointsAccount getOrCreateAccount(Long memberId);

    PointsAccount getAccount(Long memberId);
}
