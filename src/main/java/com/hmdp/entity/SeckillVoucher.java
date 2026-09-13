package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 秒杀券表，与 tb_voucher 一对一（主键即优惠券 id） */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_voucher")
public class SeckillVoucher implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "voucher_id", type = IdType.INPUT)
    private Long voucherId;

    private Integer stock;

    /** 对账账本：活动结束库存重算的基准，发布时与 stock 一致 */
    private Integer initialStock;

    private LocalDateTime createTime;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

    private LocalDateTime updateTime;

}
