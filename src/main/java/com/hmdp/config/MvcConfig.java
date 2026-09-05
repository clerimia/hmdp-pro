package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.utils.SlidingWindowInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SlidingWindowInterceptor slidingWindowInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                ).order(1);
        // 逐个 path 精确挂载，不用 /voucher-order/seckill/** —— 后者会把结果查询、
        // 支付查询全部塞进同一个限流桶，用户轮询几次就烧光自己的下单配额。
        // 落地这一步很关键：拦截器内部已经按 URI 分好了三个桶，但只注册下单 path 的话，
        // result 分支永远不会被执行，那道「查落库 10 次/秒」的限流就是空话。
        // 具体分桶规则与配额见 SlidingWindowInterceptor 类注释。
        // 注：pay 分支（GET /voucher-order/pay/result/{orderId}）暂未挂载——支付链路本轮不做，
        // 待该接口实现后在此补上 path 即可，拦截器与 yaml 配额都已就位。
        registry.addInterceptor(slidingWindowInterceptor)
                .addPathPatterns("/voucher-order/seckill/{id}",
                        "/voucher-order/seckill/result/{orderId}")
                .order(2);
    }
}
