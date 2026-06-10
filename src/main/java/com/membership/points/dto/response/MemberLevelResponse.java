package com.membership.points.dto.response;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MemberLevelResponse {

    private Long memberId;

    private String memberNo;

    private String memberName;

    private String levelCode;

    private String levelName;

    private Long accumulatedPoints;

    private BigDecimal levelMultiplier;

    private Integer nextLevelMinPoints;  // null if already max level

    private Long pointsToNextLevel;  // null if already max level
}
