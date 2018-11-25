package com.cobostack.spring.aop.advice;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * 日志通知
 *
 * @author Xu_Ming
 * @version 1.0, 2018/11/25
 */

public class LogAdvice {

	private static final Logger LOGGER = LoggerFactory.getLogger(LogAdvice.class);

	/** 前置通知方法 */
	public void beforePrint(JoinPoint joinPoint) {
		LOGGER.debug("before print log joinPoint [{}] methodName [{}] args [{}]", joinPoint,
				joinPoint.getSignature().getName(), Arrays.asList(joinPoint.getArgs()));
	}

	/** 返回后通知方法 */
	public void afterReturningPrint(JoinPoint joinPoint, Object result) {
		LOGGER.debug("after return print log joinPoint [{}] result [{}]", joinPoint, result);
	}

	/** 出现异常的通知方法 */
	public void afterThrowingPrint(JoinPoint joinPoint, Exception ex) {
		LOGGER.debug("after exception print log joinPoint [{}] Exception [{}]", joinPoint, ex.getMessage());
	}

	/** 后置通知方法(不管抛不抛异常都会走此方法) */
	public void afterPrint(JoinPoint joinPoint) {
		LOGGER.debug("after print log joinPoint [{}]", joinPoint);
	}

	/** 环绕通知方法 */
	public Object aroundPrint(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
		Object proceed;
		try {
			LOGGER.debug("around before print log proceedingJoinPoint [{}]", proceedingJoinPoint);
			proceed = proceedingJoinPoint.proceed();
		} catch (Throwable throwable) {
			LOGGER.debug("around exception print log Exception [{}]", throwable.getMessage());
			throw throwable;
		}

		LOGGER.debug("around after print log proceedingJoinPoint [{}]", proceedingJoinPoint);
		return proceed;
	}
}