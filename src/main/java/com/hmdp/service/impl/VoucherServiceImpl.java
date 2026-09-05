package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        // 初始库存与当前库存一致：对账库存重算的基准（initial_stock 不可变）
        seckillVoucher.setInitialStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 保存秒杀库存到Redis中。用 setIfAbsent 与 SeckillWarmUpServiceImpl#warmUpStock 的
        // 语义对齐（key 已存在则不覆盖）：防止旧值覆盖进行中的库存账本——「创建即 set」
        // 绕过了预热「活动已开始不回填」的防线，未来若加修改券接口会有超卖风险。
        // 新券 id 必然是新 key，此处属防御性一致。已知边界：Redis 写在事务提交前，
        // 极端提交失败会留下一个孤儿 key（新券场景风险≈0，且无订单能引用它），不搬 afterCommit。
        stringRedisTemplate.opsForValue().setIfAbsent(
                SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
    }
}
