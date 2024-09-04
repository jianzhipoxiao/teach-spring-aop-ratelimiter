# teach-spring-aop-ratelimiter
spring aop 接口限流快速入门笔记

测试：地址[点击](spring-aop-rate-limiter-demo/src/main/java/cn/xiaomurui/blog/aop/ApITest.http)

###  Aop 结合RateLimiter限流 笔记

[toc]

#### 1.需求背景

**背景**

现在需要对抽奖服务设计限流方案，防止单个服务被巨大的流量打垮，对频繁访问的用户做每秒做出限制，加入限流黑名单，从而达到保护系统的作用。

**效果展示：**

每个用户调用接口 1 秒/次，超过速度限流，3 次限流后加入黑名单 24 小时不能访问。

正常查询：

![image-20240904165044217](https://s2.loli.net/2024/09/04/zufAL2wqaSWPXZv.png)

限流后：

![image-20240904164731762](https://s2.loli.net/2024/09/04/WHMjpdDsTavG6An.png)

**设计图**

![image-20240901170111899](https://s2.loli.net/2024/09/04/1x74zuBari5lqpg.png)



**流程图**：

![image-20240904165553128](../../../../../AppData/Roaming/Typora/typora-user-images/image-20240904165553128.png)

#### 2.基本语法知识

**ProceedingJoinPoint  类**

ProceedingJoinPoint 它是 `JoinPoint` 接口的一个子接口，提供了更高级的功能，允许在方法执行前后进行控制。

**作用**

- **方法执行控制**：可以在方法执行前后进行自定义逻辑，比如记录日志、异常处理、权限校验等。
- **动态代理**：允许在目标方法执行时动态地改变其行为，比如修改输入参数或返回值。
- **性能监控**：可以用于统计方法执行时间，帮助进行性能分析。

**常用方法**

| 方法                                | 描述                                                         |
| ----------------------------------- | ------------------------------------------------------------ |
| **`Object proceed()`**              | 执行目标方法，并返回其结果。如果不调用这个方法，目标方法将不会被执行。 |
| **`Object proceed(Object[] args)`** | 执行目标方法，允许传入自定义参数。如果需要修改传入参数，可以在调用前处理这个数组。 |
| **`Signature getSignature()`**      | 获取当前连接点的方法签名（包含方法名、参数类型等）。         |
| **`Object getTarget()`**            | 获取被代理的目标对象。                                       |
| **`Object getThis()`**              | 获取当前代理对象。                                           |
| **`String toString()`**             | 返回当前连接点的信息，通常用于调试。                         |
| **`Object[] getArgs()`**            | 返回当前拦截方法的参数                                       |

**示例代码**

```java
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LoggingAspect {

    @Around("execution(* com.example.service.*.*(..))")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        Object proceed = joinPoint.proceed(); // 执行目标方法

        long executionTime = System.currentTimeMillis() - start;

        System.out.println("Method " + joinPoint.getSignature() + " executed in " + executionTime + "ms");
        return proceed;
    }
}
```

在这个示例中，`logExecutionTime` 方法使用 `ProceedingJoinPoint` 来执行目标方法并记录其执行时间。





#### 3.工程实践

> 完整工程代码：[jianzhipoxiao/teach-spring-aop-ratelimiter: spring aop 接口限流快速入门笔记 (github.com)](https://github.com/jianzhipoxiao/teach-spring-aop-ratelimiter)

项目结构

~~~apl
─spring-aop-rate-limiter-demo
    ├─src
    │  ├─main
    │  │  ├─java
    │  │  │  └─cn
    │  │  │      └─xiaomurui
    │  │  │          └─blog
    │  │  │              └─aop
    │  │  │                  ├─anotitions
    │  │  │                  ├─config
    │  │  │                  └─web
    │  │  └─resources
    │  └─test
    │      └─java
    │          └─cn
    │              └─xiaomurui
    │                  └─blog
    │                      └─springaopratelimiterdemo
~~~



##### 3.1 限流接口注解设计

自定义注解的作用是在需要做限流的接口上配置做标记方面被 aop 拦截，这在日常开发中是非常常见的手段。

```java
package cn.xiaomurui.blog.aop.anotitions;

import java.lang.annotation.*;

/**
 * @author 小木蕊
 * @address xiaomurui@163.com <a href="https://gitee.com/poxiao02">...</a>
 * @createDate 2024/8/29 22:04
 * @description 限流拦截
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface RateLimiterAccessInterceptor {
    /**
     * 限流字段。默认走全部
     */
    String key() default "all";

    /**
     * 每秒限制次数
     */
    double permitsPerSecond();

    /**
     * 限制多少次后加入黑名单，默认0次
     */
    double blacklistCount() default 0;

    /**
     * 拦截后执行方法
     */
    String fallBackMethod();

}
```



##### 3.2 aop 切面

aop 的使用推荐使用 `@Around`这也是功能最为强大的一种，核心的想法是获取到代理类的参数，尝试获取 `Guava RateLimiter `的令牌，获取到执行后续正常业务流程，如果未获取到则执行预定的失败方法，这里的 `fallBackMethod`使用反射调用，黑名单可存储在 `Guava`这类本地缓存中，这样就是单台机器限流，如果存储到 `Redis`中则是一台机器限流后该用户访问其它的机器也是限流的，可以根据业务选择。

~~~ java
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
~~~



#### 4.总结

1. aop 是日常开发中非常常用的一种手段，功能十分强大，需要系统学习，以及 aop 失效的场景
2. 限流是保护系统的一种手段，用来防止服务被恶意的攻击或巨大的流量打垮的一种手段，现成的工具类有 Guava 的 Ratelimiter.

