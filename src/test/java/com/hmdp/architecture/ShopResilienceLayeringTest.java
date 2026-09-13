package com.hmdp.architecture;

import com.hmdp.controller.ShopController;
import com.hmdp.service.impl.ShopServiceImpl;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ShopResilienceLayeringTest {

    @Test
    void cacheBulkheadShouldWrapRedisCircuitBreakerAtApiBoundary() throws NoSuchMethodException {
        Method controllerEntry = ShopController.class.getMethod("queryShopById", Long.class);
        Method serviceQuery = ShopServiceImpl.class.getMethod("queryById", Long.class);

        Bulkhead bulkhead = controllerEntry.getAnnotation(Bulkhead.class);
        CircuitBreaker circuitBreaker = serviceQuery.getAnnotation(CircuitBreaker.class);

        assertNotNull(bulkhead);
        assertEquals("cacheBulkhead", bulkhead.name());
        assertNotNull(circuitBreaker);
        assertEquals("redisBreaker", circuitBreaker.name());
        assertFalse(serviceQuery.isAnnotationPresent(Bulkhead.class),
                "舱壁和熔断器不能堆在同一个方法上，否则默认切面顺序会让 fallback 捕获舱壁拒绝");
    }
}
