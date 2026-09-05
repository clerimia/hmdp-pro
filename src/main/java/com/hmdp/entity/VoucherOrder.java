package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_voucher_order")
public class VoucherOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 领取的用户id
     */
    private Long userId;

    /**
     * 领取的优惠券id
     */
    private Long voucherId;

    /**
     * 是否已使用：0=已领取未使用；1=已使用（核销）。
     * 领券落库不写这一列，靠 DB 默认 0；核销由商家端/线下 CAS 写入（本项目不提供核销接口）
     */
    private Integer used;

    /**
     * 领取时间
     */
    private LocalDateTime createTime;

    /**
     * 核销时刻
     */
    private LocalDateTime useTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
