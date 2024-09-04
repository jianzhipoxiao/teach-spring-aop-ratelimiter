package cn.xiaomurui.blog.aop.web;

import cn.xiaomurui.blog.aop.anotitions.RateLimiterAccessInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 小木蕊
 * @address xiaomurui@163.com <a href="https://gitee.com/poxiao02">...</a>
 * @createDate 2024/9/4 16:14
 * @description
 */
@RestController
@RequestMapping("/aop")
@Slf4j
public class AopTestController {
    @RequestMapping("/test")
    public String test() {
        return "hello world";
    }

    @RequestMapping(value = "query_user",method = RequestMethod.GET)
    @RateLimiterAccessInterceptor(key = "userId",permitsPerSecond = 1,blacklistCount = 3,fallBackMethod = "queryUserError")
    public String queryUser(@RequestParam String userId){
        StringBuffer result = new StringBuffer();
        result.append(userId);
        result.append(" query user ...");
        return result.toString();
    }

    public String queryUserError(@RequestParam String userId){
        StringBuffer result = new StringBuffer();
        result.append("查询太频繁了，请稍后重试...");
        return result.toString();
    }
}
