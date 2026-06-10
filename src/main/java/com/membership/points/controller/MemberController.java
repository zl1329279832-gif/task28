package com.membership.points.controller;

import com.membership.points.common.result.Result;
import com.membership.points.dto.response.MemberLevelResponse;
import com.membership.points.service.MemberLevelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会员控制器
 */
@RestController
@RequestMapping("/api/member")
@RequiredArgsConstructor
public class MemberController {

    private final MemberLevelService memberLevelService;

    @GetMapping("/{memberId}/level")
    public Result<MemberLevelResponse> getMemberLevel(@PathVariable Long memberId) {
        MemberLevelResponse response = memberLevelService.getLevelResponse(memberId);
        return Result.ok(response);
    }
}
