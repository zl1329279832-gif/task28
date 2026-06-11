package com.example.points.service;

import com.example.points.entity.CircuitBreaker;

public interface CircuitBreakerService {

    CircuitBreaker getByPoolId(Long poolId);

    void manualClose(Long poolId, String operator);

    void manualOpen(Long poolId, String operator);

    void processRecoveryChecks();

    void recordHalfOpenSuccess(Long poolId);

    void recordHalfOpenFailure(Long poolId);

    void recordTrip(Long poolId, boolean budgetReleased);
}
