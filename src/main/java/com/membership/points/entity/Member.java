package com.membership.points.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("member")
public class Member {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("member_no")
    private String memberNo;

    @TableField("name")
    private String name;

    @TableField("phone")
    private String phone;

    @TableField("email")
    private String email;

    @TableField("level_id")
    private Long levelId;

    @TableField("accumulated_points")
    private Long accumulatedPoints;

    @TableField("register_time")
    private LocalDateTime registerTime;

    @TableField("status")
    private Integer status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
