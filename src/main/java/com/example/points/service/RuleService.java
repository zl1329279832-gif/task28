package com.example.points.service;

import com.example.points.dto.RuleUpdateRequest;
import com.example.points.entity.PointsRule;

import java.util.List;

public interface RuleService {

    List<PointsRule> listRules();

    PointsRule updateRule(RuleUpdateRequest request);

    PointsRule createRule(PointsRule rule);
}
