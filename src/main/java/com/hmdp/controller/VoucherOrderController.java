package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 秒杀异步结果查询：WAITING=已预扣待落库 / SUCCESS=已落库 / FAIL_*=落库失败。
     * 排队状态缺失时（TTL 过期或写入失败）回落到订单表兜底。
     */
    @GetMapping("seckill/result/{orderId}")
    public Result seckillResult(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.getSeckillResult(orderId);
    }
}
