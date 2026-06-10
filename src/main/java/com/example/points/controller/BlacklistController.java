package com.example.points.controller;

import com.example.points.common.ApiResponse;
import com.example.points.service.BlacklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/blacklist")
@RequiredArgsConstructor
public class BlacklistController {

    private final BlacklistService blacklistService;

    @PostMapping("/add")
    public ApiResponse<Void> addToBlacklist(
            @RequestParam Long memberId,
            @RequestParam String reason,
            @RequestParam String operator,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime expireTime) {
        blacklistService.addToBlacklist(memberId, reason, operator, expireTime);
        return ApiResponse.ok();
    }

    @PostMapping("/remove")
    public ApiResponse<Void> removeFromBlacklist(
            @RequestParam Long memberId,
            @RequestParam String operator) {
        blacklistService.removeFromBlacklist(memberId, operator);
        return ApiResponse.ok();
    }

    @GetMapping("/check/{memberId}")
    public ApiResponse<Boolean> checkBlacklisted(@PathVariable Long memberId) {
        boolean result = blacklistService.isBlacklisted(memberId);
        return ApiResponse.ok(result);
    }
}
