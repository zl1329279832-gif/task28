package com.example.points.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.points.entity.PointsFlow;

public interface PointsFlowService {

    PointsFlow checkIdempotent(String eventId);

    void saveFlow(PointsFlow flow);

    Page<PointsFlow> queryFlows(Long memberId, String eventType, int page, int size);
}
