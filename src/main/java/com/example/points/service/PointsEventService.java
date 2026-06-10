package com.example.points.service;

import com.example.points.dto.AdjustRequest;
import com.example.points.dto.PointsEventRequest;
import com.example.points.dto.RefundRequest;
import com.example.points.entity.PointsFlow;

public interface PointsEventService {

    /**
     * Process points event (register/purchase/checkin/activity). Idempotent via eventId.
     */
    PointsFlow processEvent(PointsEventRequest request);

    /**
     * Manual adjustment of points
     */
    PointsFlow adjust(AdjustRequest request);

    /**
     * Refund: return points based on rules
     */
    PointsFlow refund(RefundRequest request);
}
