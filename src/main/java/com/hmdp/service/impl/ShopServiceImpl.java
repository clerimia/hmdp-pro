package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.MultiLevelCacheService;
import com.hmdp.utils.SystemConstants;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    /** 压测对照链路使用裸 Shop JSON，必须与主链路 RedisData 包装格式隔离。 */
    private static final String BENCHMARK_SHOP_KEY = "cache:shop:benchmark:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private MultiLevelCacheService multiLevelCache;

    /** 手动获取 dbFallbackBulkhead 许可用（fallback 不走 Spring 代理，注解失效） */
    @Resource
    private BulkheadRegistry bulkheadRegistry;

    /** 降级打点（P3）：fallback 必须可见，见 docs/observability.md */
    @Resource
    private com.hmdp.observability.ResilienceMetrics resilienceMetrics;

    @Resource
    private com.hmdp.observability.CacheMetrics cacheMetrics;

    /**
     * Redis 容错入口。外层 {@code cacheBulkhead} 位于 Controller，本层只处理缓存依赖故障；
     * 降级回源另由 {@code dbFallbackBulkhead} 限制为 20 个并发，避免故障流量压垮数据库。
     */
    @CircuitBreaker(name = "redisBreaker", fallbackMethod = "queryByIdFallback")
    @Override
    public Result queryById(Long id) {
        Shop shop = multiLevelCache.queryWithMultiLevelVersioned(
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES,
                Shop::getUpdateTime,
                shopId -> baseMapper.selectUpdateTimeById(shopId));

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    /**
     * redisBreaker 的降级：回源 DB。
     *
     * <p><b>为什么手动获取舱壁许可</b>：fallbackMethod 由 R4J 切面反射直调，
     * 不经过 Spring 代理，方法上的任何注解都不会生效——所以 dbFallbackBulkhead
     * 只能通过 {@link BulkheadRegistry} 手动 tryAcquirePermission（yaml 注释里同样的说明）。
     */
    // Resilience4j 1.7.1 通过反射调用 fallback；保持 public，避免高并发下私有方法
    // 偶发 IllegalAccessException 被包装成 UndeclaredThrowableException，错误升级为 HTTP 500。
    public Result queryByIdFallback(Long id, Throwable t) {
        // 降级触发原因：熔断打开（not_permitted）还是学习期真实失败（error）
        resilienceMetrics.fallback("redisBreaker", t instanceof CallNotPermittedException
                ? com.hmdp.observability.ResilienceMetrics.KIND_NOT_PERMITTED
                : com.hmdp.observability.ResilienceMetrics.KIND_ERROR);
        log.warn("Redis 查询降级回源 DB, shopId={}, cause={}", id, t.toString());
        Bulkhead bulkhead = bulkheadRegistry.bulkhead("dbFallbackBulkhead");
        if (!bulkhead.tryAcquirePermission()) {
            // 回源链路也满了：宁可再拒一个，也不让 DB 被打穿
            resilienceMetrics.fallback("redisBreaker",
                    com.hmdp.observability.ResilienceMetrics.KIND_BULKHEAD_REJECTED);
            throw BulkheadFullException.createBulkheadFullException(bulkhead);
        }
        try {
            Shop shop = getById(id);
            if (shop == null) {
                return Result.fail("店铺不存在！");
            }
            return Result.ok(shop);
        } finally {
            bulkhead.releasePermission();
        }
    }

    /** 黑马原版读路径对照：仅 Redis → MySQL（无 Caffeine / 无网关 L1） */
    @Override
    public Result queryByIdHeimaRedis(Long id) {
        Shop shop = cacheClient
                .queryWithPassThrough(BENCHMARK_SHOP_KEY, id, Shop.class, this::getById,
                        CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 校验影响行数：id 不存在时 updateById 影响 0 行返回 false，
        // 原实现忽略返回值——对不存在的店铺伪报成功，还触发一次无意义的缓存删除
        boolean updated = updateById(shop);
        if (!updated) {
            return Result.fail("店铺不存在！");
        }
        // 事务提交后再失效多级缓存（Caffeine + Redis）：
        //   - 缓存组件故障不应阻断业务主流程（此前写在事务内，Redis 故障会连带业务回滚）；
        //   - 事务回滚时也不会留下「缓存已删 + 库里还是旧值」的错位。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictCacheWithRetry(CACHE_SHOP_KEY, id, 2);
                }
            });
        } else {
            // 防御：无事务上下文时直接同步删，不因环境变化悄悄失效
            evictCacheWithRetry(CACHE_SHOP_KEY, id, 2);
        }
        return Result.ok();
    }

    /**
     * 提交后删除缓存：瞬时抖动靠短退避重试自愈；仍失败只记日志告警、绝不抛出——
     * 此时 DB 已提交，一致性由物理 TTL 保险丝兜底收敛，
     * 最迟 3 倍逻辑 TTL（cache:shop 为 90min）内自愈。
     */
    private void evictCacheWithRetry(String keyPrefix, Long id, int maxRetries) {
        for (int i = 0; ; i++) {
            try {
                multiLevelCache.evict(keyPrefix, id);
                cacheMetrics.evicted(true);
                return;
            } catch (Exception e) {
                if (i >= maxRetries) {
                    cacheMetrics.evicted(false);
                    log.error("缓存删除失败，等待物理 TTL 兜底, key={}, retries={}",
                            keyPrefix + id, maxRetries, e);
                    return;
                }
                try {
                    Thread.sleep(100L << i);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("缓存删除重试被中断, key={}", keyPrefix + id, ie);
                    return;
                }
            }
        }
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 无坐标：退化为按类型的普通分页查询
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        // 不足一页的偏移量：没有下一页了
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr, result.getDistance());
        });
        // 按 Redis 给出的距离顺序回表，保证结果顺序与 GEO 排序一致
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
