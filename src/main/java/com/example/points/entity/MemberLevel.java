package com.example.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("member_level")
public class MemberLevel {

    @TableId
    private Long id;

    @TableField("level_code")
    private Integer levelCode;

    @TableField("level_name")
    private String levelName;

    @TableField("min_exp")
    private Long minExp;

    @TableField("earn_rate")
    private BigDecimal earnRate;

    @TableField("redeem_rate")
    private BigDecimal redeemRate;

    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
