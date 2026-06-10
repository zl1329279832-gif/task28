package com.example.points.service;

import com.example.points.dto.PointsEventRequest;
import com.example.points.entity.PointsAccount;
import com.example.points.entity.PointsRule;

import java.util.List;

public interface RuleEngine {

    /**
     * Calculate points for a given event. Returns the final points after applying all rules.
     * Applies: base rule -> activity multiplier -> level multiplier -> monthly cap
     */
    long calculatePoints(PointsEventRequest request, PointsAccount account);

    /**
     * Get all active rules
     */
    List<PointsRule> getActiveRules();

    /**
     * Get active rule by code
     */
    PointsRule getActiveRule(String ruleCode);
}
