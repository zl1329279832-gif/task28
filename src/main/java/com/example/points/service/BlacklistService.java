package com.example.points.service;

import java.time.LocalDateTime;

public interface BlacklistService {

    void addToBlacklist(Long memberId, String reason, String operator, LocalDateTime expireTime);

    void removeFromBlacklist(Long memberId, String operator);

    boolean isBlacklisted(Long memberId);
}
