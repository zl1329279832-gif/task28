package com.example.points.service.impl;

import com.example.points.entity.AuditLog;
import com.example.points.mapper.AuditLogMapper;
import com.example.points.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;

    @Override
    public void log(String module, String action, String targetId, String targetType,
                    String beforeValue, String afterValue, String operator, String ip) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .module(module)
                    .action(action)
                    .targetId(targetId)
                    .targetType(targetType)
                    .beforeValue(beforeValue)
                    .afterValue(afterValue)
                    .operator(operator)
                    .ip(ip)
                    .createTime(LocalDateTime.now())
                    .build();
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.error("Failed to save audit log: module={}, action={}, targetId={}", module, action, targetId, e);
        }
    }
}
