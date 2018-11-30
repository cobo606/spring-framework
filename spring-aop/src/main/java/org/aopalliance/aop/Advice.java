/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.aopalliance.aop;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.aspectj.AspectJAfterAdvice;
import org.springframework.aop.aspectj.AspectJAfterReturningAdvice;
import org.springframework.aop.aspectj.AspectJAfterThrowingAdvice;
import org.springframework.aop.aspectj.AspectJAroundAdvice;
import org.springframework.aop.aspectj.AspectJMethodBeforeAdvice;

import java.lang.reflect.Method;

/**
 * Tag interface for Advice. Implementations can be any type
 * of advice, such as Interceptors.
 *
 * <p> 通知 Advice 即我们定义的横切逻辑, 比如我们可以定义一个用于监控方法性能的通知, 也可以定义一个输出日志的通知等.
 * <p> 通知逻辑在什么时候被调用呢? 通过切点可以解决通知在哪些方法上需要调用通知, 但是目标方法执行前被调用,
 *  还是目标方法执行后被调用呢? Spring 中定义了以下几种通知类型:
 * <ul>
 * <li> 前置通知（Before advice）- 在目标方便调用前执行通知 {@link AspectJMethodBeforeAdvice#before(Method, Object[], Object)}
 * <li> 后置通知（After advice）- 在目标方法完成后执行通知 {@link AspectJAfterAdvice#invoke(MethodInvocation)}
 * <li> 返回通知（After returning advice）- 在目标方法执行成功后, 调用通知 {@link AspectJAfterReturningAdvice#afterReturning(Object, Method, Object[], Object)}
 * <li> 异常通知（After throwing advice）- 在目标方法抛出异常后, 执行通知 {@link AspectJAfterThrowingAdvice#invoke(MethodInvocation)}
 * <li> 环绕通知（Around advice）- 在目标方法调用前后均可执行自定义逻辑 {@link AspectJAroundAdvice#invoke(MethodInvocation)}
 * </ul>
 *
 * @author Rod Johnson
 * @version $Id: Advice.java,v 1.1 2004/03/19 17:02:16 johnsonr Exp $
 */
public interface Advice {

}
