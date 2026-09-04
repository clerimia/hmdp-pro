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
// 注解 @Bulkhead 与上面的核心类同名，这里用全限定名写在方法上（见 queryById）
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

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


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

    /**
     * 查询入口（P1 容错）：
     * <ul>
     *   <li>{@code @Bulkhead(cacheBulkhead)}：信号量 50，许可耗尽立即拒，先在隔离层挡住</li>
     *   <li>{@code @CircuitBreaker(redisBreaker)}：Redis 失败计入 redisBreaker，打开后快速失败</li>
     * </ul>
     * 降级 = 回源 DB。切到新链路的同时必须限流隔离（dbFallbackBulkhead，许可数对齐 Hikari 池 20），
     * 否则 Redis 挂掉后所有读涌向 DB，降级本身就成了雪崩放大器。
     */
    @io.github.resilience4j.bulkhead.annotation.Bulkhead(name = "cacheBulkhead",
            type = io.github.resilience4j.bulkhead.annotation.Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "redisBreaker", fallbackMethod = "queryByIdFallback")
    @Override
    public Result queryById(Long id) {
        // 多级缓存：Caffeine(L1 JVM) → Redis逻辑过期(L2) → MySQL(L3)
        Shop shop = multiLevelCache
                .queryWithMultiLevel(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

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
     *
     * <p><b>舱壁拒绝不再回源</b>：cacheBulkhead 打回 ≠ Redis 故障。若降级也回源 DB，
     * 高并发下一半流量被隔离层挡住后又会涌向 DB，隔离层形同虚设。
     */
    private Result queryByIdFallback(Long id, Throwable t) {
        if (t instanceof BulkheadFullException) {
            // cacheBulkhead 打回 ≠ Redis 故障，但也属于「请求没走正常链路」的韧性动作，一并可见
            resilienceMetrics.fallback("redisBreaker",
                    com.hmdp.observability.ResilienceMetrics.KIND_BULKHEAD_REJECTED);
            throw (BulkheadFullException) t;
        }
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

    /**
     * 黑马原版读路径对照：仅 Redis → MySQL（无 Caffeine / 无网关 L1）
     */
    @Override
    public Result queryByIdHeimaRedis(Long id) {
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
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
        // 1.更新数据库
        updateById(shop);
        // 2.事务提交后再失效多级缓存（Caffeine + Redis）：
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
                return;
            } catch (Exception e) {
                if (i >= maxRetries) {
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
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
