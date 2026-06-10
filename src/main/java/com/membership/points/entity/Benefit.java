package com.membership.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("benefit")
public class Benefit {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("benefit_code")
    private String benefitCode;

    @TableField("benefit_name")
    private String benefitName;

    @TableField("description")
    private String description;

    @TableField("category")
    private String category;

    @TableField("points_cost")
    private Integer pointsCost;

    @TableField("min_level")
    private String minLevel;

    @TableField("image_url")
    private String imageUrl;

    @TableField("enabled")
    private Integer enabled;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
