package cn.xiaomurui.blog.aop.config;

import cn.xiaomurui.blog.aop.anotitions.RateLimiterAccessInterceptor;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * @author 小木蕊
 * @address xiaomurui@163.com <a href="https://gitee.com/poxiao02">...</a>
 * @createDate 2024/8/31 9:41
 * @description 限流 AOP 切面拦截
 */
@Slf4j
@Aspect
@Component
public class RateLimiterAop {

    // 限流开关
    private String rateLimiterSwitch = "open";

    // 个人1分钟限流
    private final Cache<String, RateLimiter> loginRecord = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.SECONDS).build();

    // 用户限流黑名单单机限流可替换为 redis 全局限流
    private final Cache<String, Long> blackList = CacheBuilder.newBuilder().expireAfterWrite(24, TimeUnit.HOURS).build();

    @Pointcut("@annotation(cn.xiaomurui.blog.aop.anotitions.RateLimiterAccessInterceptor)")
    public void aopPoint() {
    }

    @Around("aopPoint()&& @annotation(rateLimiterAccessInterceptor)")
    public Object dbRouter(ProceedingJoinPoint joinPoint, RateLimiterAccessInterceptor rateLimiterAccessInterceptor) throws Throwable {
        // 1.检查限流配置 【open】开启限流，【close】关闭直接放行
        if (StringUtils.isBlank(rateLimiterSwitch) || "close".equals(rateLimiterSwitch)) {
            return joinPoint.proceed();
        }

        // 2.反射获取拦截字段值
        String key = rateLimiterAccessInterceptor.key();
        if (StringUtils.isBlank(key)) {
            throw new RuntimeException("RateLimiter uId is null!");
        }
        String keyAttr = getAttrValue(key, joinPoint.getArgs());

        // 3.检查黑名单和限流策略如： 根据用户 uid
        if (!"all".equals(key) && rateLimiterAccessInterceptor.blacklistCount() != 0 && null!=blackList.getIfPresent(keyAttr) && blackList.getIfPresent(keyAttr) > rateLimiterAccessInterceptor.blacklistCount()) {
            return fallBackMethodResult(joinPoint, rateLimiterAccessInterceptor.fallBackMethod());
        }

        // 4.限流器获取
        RateLimiter rateLimiter = loginRecord.getIfPresent(keyAttr);
        if (ObjectUtils.isEmpty(rateLimiter)) {
            rateLimiter = RateLimiter.create(rateLimiterAccessInterceptor.permitsPerSecond());
            loginRecord.put(keyAttr, rateLimiter);
        }

        // 5.限流处理
        if (!rateLimiter.tryAcquire()) {
            if (rateLimiterAccessInterceptor.blacklistCount() != 0) {
                if (ObjectUtils.isEmpty(blackList.getIfPresent(keyAttr))) {
                    blackList.put(keyAttr, 1L);
                } else {
                    blackList.put(keyAttr, blackList.getIfPresent(keyAttr) + 1);
                }
            }
            return fallBackMethodResult(joinPoint, rateLimiterAccessInterceptor.fallBackMethod());
        }

        // 6.放行
        return joinPoint.proceed();
    }

    /**
     * 拦截后置处理
     *
     * @param joinPoint      切点配置
     * @param fallBackMethod 目标接口限流后处理方法名，入参需要保持一致
     * @return 反射调用限流后置处理方法
     */
    private Object fallBackMethodResult(ProceedingJoinPoint joinPoint, String fallBackMethod) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Signature signature = joinPoint.getSignature();
        MethodSignature methodSignature = (MethodSignature) signature;
        Method method = joinPoint.getTarget().getClass().getMethod(fallBackMethod, methodSignature.getParameterTypes());
        return method.invoke(joinPoint.getThis(), joinPoint.getArgs());
    }


    /**
     * 获取属性值
     *
     * @param attr 属性名称
     * @param args 代理方法 参数合集
     * @return 目标属性值
     */
    public String getAttrValue(String attr, Object[] args) {
        if (args[0] instanceof String) {
            return args[0].toString();
        }

        String fileValue = null;
        for (Object arg : args) {
            if (StringUtils.isNotBlank(fileValue)) {
                break;
            }

            // filedValue = BeanUtils.getProperty(arg, attr);
            // fix: 使用lombok时，uId这种字段的get方法与idea生成的get方法不同，会导致获取不到属性值，改成反射获取解决
            fileValue = String.valueOf(this.getValueByName(arg, attr));
        }
        return fileValue;
    }

    /**
     * 获取对象的属性值
     *
     * @param item 目标对象
     * @param attr 属性名
     * @return 目标对象的属性的值
     */

    private Object getValueByName(Object item, String attr) {
        try {
            Field filed = this.getFiledByName(item, attr);
            if (ObjectUtils.isEmpty(filed)) {
                return null;
            }
            filed.setAccessible(true);
            Object o = filed.get(item);
            filed.setAccessible(false);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 根据属性名获取对应属性对象
     *
     * @param item 目标属性对像
     * @param name 属性名
     * @return 目标属性
     */
    private Field getFiledByName(Object item, String name) {
        try {
            Field field;
            try {
                field = item.getClass().getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                field = item.getClass().getSuperclass().getDeclaredField(name);
            }
            return field;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

}
