package com.lxqnsys.doc.aspect.lock;

import cn.hutool.json.JSONUtil;
import com.laxqnsys.common.enums.ErrorCodeEnum;
import com.laxqnsys.common.exception.BusinessException;
import com.laxqnsys.common.util.RedissonLock;
import com.lxqnsys.doc.context.LoginContext;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.ognl.Ognl;
import org.apache.ibatis.ognl.OgnlException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Aspect
@Component
public class ConcurrentLockAspect {

    @Autowired
    private RedissonLock redissonLock;
    // 非贪吃模式匹配
    private Pattern pattern = Pattern.compile("(\\$\\{)([\\w\\W]+?)(\\})");

    /**
     * {@link ConcurrentLock}
     */
    @Pointcut("@annotation(com.lxqnsys.doc.aspect.lock.ConcurrentLock))")
    public void pointCut() {
        // 仅仅是为了设置切点
    }

    @Around("pointCut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method method = methodSignature.getMethod();
        Object[] parameters = joinPoint.getArgs();
        String[] parameterNames = methodSignature.getParameterNames();
        ConcurrentLock lock = method.getAnnotation(ConcurrentLock.class);

        if (lock != null && org.springframework.util.StringUtils.hasText(lock.key())) {
            String key;
            try {
                key = this.getKey(lock, parameterNames, parameters);
            } catch (Exception e) {
                log.error("获取redis key 失败: method={}, key={}", method.getName(), lock.key(), e);
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "获取锁key失败");
            }
            // 过期时间小于0、不合格
            if (lock.expire() <= 0) {
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "expire必须设置大于0");
            }
            Callable<Object> callable = () -> {
                try {
                    return joinPoint.proceed();
                } catch (BusinessException e) {
                    throw e;
                } catch (Throwable e) {
                    throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "调用失败！", e);
                }
            };
            return redissonLock.tryLock(key, lock.expire(), TimeUnit.SECONDS, callable);
        } else {
            return joinPoint.proceed();
        }
    }

    private String getKey(ConcurrentLock lock, String[] parameterNames, Object[] parameters) throws OgnlException {
        String key = lock.key();
        Map<String, Object> context = new HashMap<>();
        for (int i = 0; i < parameters.length; i++) {
            // 以后根据需要加过滤， request和multipartFile类型的数据不可能作为redis key 主键内容
            if (!(parameters[i] instanceof HttpServletRequest || parameters[i] instanceof MultipartFile)) {
                context.put(parameterNames[i], parameters[i]);
            }
        }
        context.put("userInfoBO", LoginContext.getUserInfo());
        StringBuffer sb = new StringBuffer();
        Matcher matcher = pattern.matcher(key);
        while (matcher.find()) {
            Object value = Ognl.getValue(matcher.group(2), context);
            if (value == null) {
                log.error("未找到对应值: key={}, parameters={}", lock.key(), JSONUtil.toJsonStr(parameters));
                throw new OgnlException("未找到对应值");
            }
            matcher.appendReplacement(sb, String.valueOf(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
