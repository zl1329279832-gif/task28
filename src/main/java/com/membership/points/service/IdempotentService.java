package com.membership.points.service;

/**
 * 幂等性服务接口
 * 用于防止重复请求处理
 */
public interface IdempotentService {

    /**
     * 检查并标记幂等键
     * 如果是新请求则标记为PROCESSING并返回true，如果是重复请求则返回false
     *
     * @param idempotentKey 幂等键
     * @param businessType  业务类型
     * @return true=新请求可继续处理，false=重复请求
     */
    boolean checkAndMark(String idempotentKey, String businessType);

    /**
     * 获取缓存的处理结果
     *
     * @param idempotentKey 幂等键
     * @return 缓存的结果数据，无则返回null
     */
    String getCachedResult(String idempotentKey);

    /**
     * 标记处理结果
     * 业务处理成功后更新结果数据
     *
     * @param idempotentKey 幂等键
     * @param resultData    结果数据
     */
    void markResult(String idempotentKey, String resultData);

    /**
     * 清理过期的幂等记录
     */
    void cleanExpired();
}
