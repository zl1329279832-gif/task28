package com.membership.points.controller;

import com.membership.points.common.result.Result;
import com.membership.points.dto.request.BlacklistRequest;
import com.membership.points.service.BlacklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 黑名单控制器
 */
@RestController
@RequestMapping("/api/blacklist")
@RequiredArgsConstructor
public class BlacklistController {

    private final BlacklistService blacklistService;

    @PostMapping
    public Result<?> addToBlacklist(@Valid @RequestBody BlacklistRequest request) {
        blacklistService.addToBlacklist(request);
        return Result.ok();
    }

    @DeleteMapping("/{memberId}")
    public Result<?> removeFromBlacklist(
            @PathVariable Long memberId,
            @RequestParam String blockType,
            @RequestParam String operator) {
        blacklistService.removeFromBlacklist(memberId, blockType, operator);
        return Result.ok();
    }
}
