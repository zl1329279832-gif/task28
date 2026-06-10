package com.example.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.points.entity.PointsFlow;
import com.example.points.mapper.PointsFlowMapper;
import com.example.points.service.PointsFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointsFlowServiceImpl implements PointsFlowService {

    private final PointsFlowMapper pointsFlowMapper;

    @Override
    public PointsFlow checkIdempotent(String eventId) {
        LambdaQueryWrapper<PointsFlow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PointsFlow::getEventId, eventId);
        List<PointsFlow> flows = pointsFlowMapper.selectList(wrapper);
        return flows.isEmpty() ? null : flows.get(0);
    }

    @Override
    public void saveFlow(PointsFlow flow) {
        pointsFlowMapper.insert(flow);
    }

    @Override
    public Page<PointsFlow> queryFlows(Long memberId, String eventType, int page, int size) {
        Page<PointsFlow> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<PointsFlow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PointsFlow::getMemberId, memberId);
        if (eventType != null && !eventType.isEmpty()) {
            wrapper.eq(PointsFlow::getEventType, eventType);
        }
        wrapper.orderByDesc(PointsFlow::getCreateTime);
        return pointsFlowMapper.selectPage(pageParam, wrapper);
    }
}
