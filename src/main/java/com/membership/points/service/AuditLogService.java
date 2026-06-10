package com.membership.points.service;

/**
 * 审计日志服务接口
 */
public interface AuditLogService {

    /**
     * 记录审计日志
     *
     * @param operation  操作类型
     * @param targetType 目标类型
     * @param targetId   目标ID
     * @param memberId   会员ID
     * @param operator   操作人
     * @param detail     详细描述
     */
    void log(String operation, String targetType, Long targetId, Long memberId, String operator, String detail);
}
