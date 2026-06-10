package com.membership.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("benefit_inventory")
public class BenefitInventory {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("benefit_id")
    private Long benefitId;

    @TableField("sku_code")
    private String skuCode;

    @TableField("sku_name")
    private String skuName;

    @TableField("total_stock")
    private Integer totalStock;

    @TableField("available_stock")
    private Integer availableStock;

    @TableField("frozen_stock")
    private Integer frozenStock;

    @Version
    @TableField("version")
    private Integer version;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
