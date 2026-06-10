package com.example.points.service;

public interface AuditLogService {

    void log(String module, String action, String targetId, String targetType,
             String beforeValue, String afterValue, String operator, String ip);
}
