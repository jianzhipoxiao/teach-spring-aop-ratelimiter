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
