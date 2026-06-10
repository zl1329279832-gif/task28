package com.membership.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.membership.points.dto.request.CreateBenefitRequest;
import com.membership.points.dto.response.BenefitResponse;
import com.membership.points.dto.response.PageResponse;
import com.membership.points.entity.Benefit;
import com.membership.points.entity.BenefitInventory;
import com.membership.points.mapper.BenefitInventoryMapper;
import com.membership.points.mapper.BenefitMapper;
import com.membership.points.service.AuditLogService;
import com.membership.points.service.BenefitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 权益服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BenefitServiceImpl implements BenefitService {

    private final BenefitMapper benefitMapper;
    private final BenefitInventoryMapper benefitInventoryMapper;
    private final AuditLogService auditLogService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Benefit createBenefit(CreateBenefitRequest request) {
        // Build Benefit entity from request
        Benefit benefit = new Benefit();
        benefit.setBenefitCode(request.getBenefitCode());
        benefit.setBenefitName(request.getBenefitName());
        benefit.setDescription(request.getDescription());
        benefit.setCategory(request.getCategory());
        benefit.setPointsCost(request.getPointsCost());
        benefit.setMinLevel(request.getMinLevel());
        benefit.setImageUrl(request.getImageUrl());
        benefit.setEnabled(1);
        benefit.setStartTime(request.getStartTime());
        benefit.setEndTime(request.getEndTime());
        benefit.setCreatedAt(LocalDateTime.now());
        benefit.setUpdatedAt(LocalDateTime.now());

        benefitMapper.insert(benefit);

        // Build BenefitInventory
        BenefitInventory inventory = new BenefitInventory();
        inventory.setBenefitId(benefit.getId());
        inventory.setSkuCode(request.getSkuCode());
        inventory.setSkuName(request.getSkuName());
        inventory.setTotalStock(request.getTotalStock());
        inventory.setAvailableStock(request.getTotalStock());
        inventory.setFrozenStock(0);
        inventory.setVersion(0);
        inventory.setCreatedAt(LocalDateTime.now());
        inventory.setUpdatedAt(LocalDateTime.now());

        benefitInventoryMapper.insert(inventory);

        // Log audit
        auditLogService.log("CREATE_BENEFIT", "BENEFIT", benefit.getId(), null, null,
                "创建权益: " + benefit.getBenefitName() + ", SKU: " + request.getSkuCode()
                        + ", 库存: " + request.getTotalStock());

        log.info("创建权益成功, benefitId={}, benefitCode={}", benefit.getId(), benefit.getBenefitCode());
        return benefit;
    }

    @Override
    public PageResponse<BenefitResponse> listBenefits(int pageNum, int pageSize) {
        Page<Benefit> page = new Page<>(pageNum, pageSize);

        LambdaQueryWrapper<Benefit> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Benefit::getEnabled, 1)
                .orderByDesc(Benefit::getCreatedAt);

        Page<Benefit> benefitPage = benefitMapper.selectPage(page, wrapper);

        // Convert each Benefit to BenefitResponse, loading inventory for availableStock
        List<BenefitResponse> responseList = benefitPage.getRecords().stream()
                .map(this::convertToBenefitResponse)
                .collect(Collectors.toList());

        // Build PageResponse manually since we're mapping to a different type
        PageResponse<BenefitResponse> response = new PageResponse<>();
        response.setRecords(responseList);
        response.setTotal(benefitPage.getTotal());
        response.setPageNum((int) benefitPage.getCurrent());
        response.setPageSize((int) benefitPage.getSize());
        response.setTotalPages((int) Math.ceil((double) benefitPage.getTotal() / benefitPage.getSize()));

        return response;
    }

    @Override
    public Benefit getBenefitById(Long benefitId) {
        return benefitMapper.selectById(benefitId);
    }

    @Override
    public BenefitInventory getInventoryBySkuCode(String skuCode) {
        LambdaQueryWrapper<BenefitInventory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BenefitInventory::getSkuCode, skuCode);
        return benefitInventoryMapper.selectOne(wrapper);
    }

    /**
     * 将Benefit实体转换为BenefitResponse
     *
     * @param benefit 权益实体
     * @return 权益响应
     */
    private BenefitResponse convertToBenefitResponse(Benefit benefit) {
        BenefitResponse response = new BenefitResponse();
        response.setId(benefit.getId());
        response.setBenefitCode(benefit.getBenefitCode());
        response.setBenefitName(benefit.getBenefitName());
        response.setDescription(benefit.getDescription());
        response.setCategory(benefit.getCategory());
        response.setPointsCost(benefit.getPointsCost());
        response.setMinLevel(benefit.getMinLevel());
        response.setImageUrl(benefit.getImageUrl());
        response.setStartTime(benefit.getStartTime());
        response.setEndTime(benefit.getEndTime());

        // Load inventory to get availableStock
        LambdaQueryWrapper<BenefitInventory> invWrapper = new LambdaQueryWrapper<>();
        invWrapper.eq(BenefitInventory::getBenefitId, benefit.getId());
        BenefitInventory inventory = benefitInventoryMapper.selectOne(invWrapper);
        if (inventory != null) {
            response.setAvailableStock(inventory.getAvailableStock());
        } else {
            response.setAvailableStock(0);
        }

        return response;
    }
}
