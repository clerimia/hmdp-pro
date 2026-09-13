package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
@TableName("tb_voucher_order")
public class VoucherOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 雪花 ID，由 UidGenerator 生成 —— IdType.INPUT 不走自增 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long userId;

    private Long voucherId;

    /**
     * 0=已领取未使用；1=已使用（核销）。
     * 领券落库不写这一列，靠 DB 默认 0；核销由商家端/线下 CAS 写入（本项目不提供核销接口）
     */
    private Integer used;

    /** 领取时间 */
    private LocalDateTime createTime;

    /** 核销时刻 */
    private LocalDateTime useTime;

    private LocalDateTime updateTime;

}
