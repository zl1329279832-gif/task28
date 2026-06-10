package com.membership.points.service.impl;

import com.membership.points.entity.AuditLog;
import com.membership.points.mapper.AuditLogMapper;
import com.membership.points.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计日志服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;

    @Async
    @Override
    public void log(String operation, String targetType, Long targetId, Long memberId, String operator, String detail) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setOperation(operation);
            auditLog.setTargetType(targetType);
            auditLog.setTargetId(targetId);
            auditLog.setMemberId(memberId);
            auditLog.setOperator(operator);
            auditLog.setDetail(detail);
            auditLog.setCreatedAt(LocalDateTime.now());

            auditLogMapper.insert(auditLog);
            log.debug("Audit log recorded: operation={}, targetType={}, targetId={}", operation, targetType, targetId);
        } catch (Exception e) {
            log.error("Failed to record audit log: operation={}, targetType={}, targetId={}", operation, targetType, targetId, e);
        }
    }
}
