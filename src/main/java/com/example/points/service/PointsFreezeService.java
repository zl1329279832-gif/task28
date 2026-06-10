package com.example.points.service;

import com.example.points.dto.FreezeRequest;
import com.example.points.entity.PointsFreeze;

public interface PointsFreezeService {

    PointsFreeze freeze(FreezeRequest request);

    void unfreeze(String freezeNo);

    void settleFreeze(String freezeNo);
}
