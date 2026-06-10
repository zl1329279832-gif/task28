package com.membership.points.service;

import com.membership.points.dto.request.CreateBenefitRequest;
import com.membership.points.dto.response.BenefitResponse;
import com.membership.points.dto.response.PageResponse;
import com.membership.points.entity.Benefit;
import com.membership.points.entity.BenefitInventory;

/**
 * 权益服务接口
 */
public interface BenefitService {

    /**
     * 创建权益
     *
     * @param request 创建权益请求
     * @return 权益实体
     */
    Benefit createBenefit(CreateBenefitRequest request);

    /**
     * 分页查询权益列表
     *
     * @param pageNum  页码
     * @param pageSize 每页大小
     * @return 分页权益响应
     */
    PageResponse<BenefitResponse> listBenefits(int pageNum, int pageSize);

    /**
     * 根据ID获取权益
     *
     * @param benefitId 权益ID
     * @return 权益实体
     */
    Benefit getBenefitById(Long benefitId);

    /**
     * 根据SKU编码获取库存
     *
     * @param skuCode SKU编码
     * @return 权益库存
     */
    BenefitInventory getInventoryBySkuCode(String skuCode);
}
