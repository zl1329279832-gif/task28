package com.example.points.controller;

import com.example.points.common.ApiResponse;
import com.example.points.dto.AdjustRequest;
import com.example.points.dto.FreezeRequest;
import com.example.points.dto.PointsEventRequest;
import com.example.points.dto.RefundRequest;
import com.example.points.entity.PointsFlow;
import com.example.points.entity.PointsFreeze;
import com.example.points.service.PointsEventService;
import com.example.points.service.PointsFreezeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointsController {

    private final PointsEventService eventService;
    private final PointsFreezeService freezeService;

    @PostMapping("/event")
    public ApiResponse<PointsFlow> processEvent(@Valid @RequestBody PointsEventRequest request) {
        PointsFlow flow = eventService.processEvent(request);
        return ApiResponse.ok(flow);
    }

    @PostMapping("/adjust")
    public ApiResponse<PointsFlow> adjust(@Valid @RequestBody AdjustRequest request) {
        PointsFlow flow = eventService.adjust(request);
        return ApiResponse.ok(flow);
    }

    @PostMapping("/refund")
    public ApiResponse<PointsFlow> refund(@Valid @RequestBody RefundRequest request) {
        PointsFlow flow = eventService.refund(request);
        return ApiResponse.ok(flow);
    }

    @PostMapping("/freeze")
    public ApiResponse<PointsFreeze> freeze(@Valid @RequestBody FreezeRequest request) {
        PointsFreeze freeze = freezeService.freeze(request);
        return ApiResponse.ok(freeze);
    }

    @PostMapping("/freeze/{freezeNo}/unfreeze")
    public ApiResponse<Void> unfreeze(@PathVariable String freezeNo) {
        freezeService.unfreeze(freezeNo);
        return ApiResponse.ok();
    }

    @PostMapping("/freeze/{freezeNo}/settle")
    public ApiResponse<Void> settle(@PathVariable String freezeNo) {
        freezeService.settleFreeze(freezeNo);
        return ApiResponse.ok();
    }
}
