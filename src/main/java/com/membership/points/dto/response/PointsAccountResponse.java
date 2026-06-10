package com.membership.points.dto.response;

import lombok.Data;

@Data
public class PointsAccountResponse {

    private Long memberId;

    private String memberNo;

    private String memberName;

    private String levelCode;

    private String levelName;

    private Long availablePoints;

    private Long frozenPoints;

    private Long totalEarned;

    private Long totalSpent;

    private Long totalExpired;
}
