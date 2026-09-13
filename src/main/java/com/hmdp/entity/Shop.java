package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_shop")
public class Shop implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    private Long typeId;

    /** 多个图片以 ',' 隔开 */
    private String images;

    /** 商圈，例如陆家嘴 */
    private String area;

    private String address;

    /** 经度 */
    private Double x;

    /** 纬度 */
    private Double y;

    /** 均价，取整数 */
    private Long avgPrice;

    private Integer sold;

    private Integer comments;

    /** 1~5 分，乘 10 保存，避免小数 */
    private Integer score;

    /** 例如 10:00-22:00 */
    private String openHours;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableField(exist = false)
    private Double distance;
}
