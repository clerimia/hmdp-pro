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
        // 逐个 path 精确挂载，不用 /voucher-order/seckill/** —— 后者会把领券与结果查询
        // 全部塞进同一个限流桶，用户轮询几次就烧光自己的领券配额。
        // 落地这一步很关键：拦截器内部已经按 URI 分好了两个桶，但只注册领券 path 的话，
        // result 分支永远不会被执行，那道「查落库 10 次/秒」的限流就是空话。
        // 具体分桶规则与配额见 SlidingWindowInterceptor 类注释。
        registry.addInterceptor(slidingWindowInterceptor)
                .addPathPatterns("/voucher-order/seckill/{id}",
                        "/voucher-order/seckill/result/{orderId}")
                .order(2);
    }
}
