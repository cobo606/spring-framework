/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.aop.framework;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.Advisor;
import org.springframework.aop.AopInvocationException;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.RawTargetAccess;
import org.springframework.aop.TargetSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.cglib.core.ClassGenerator;
import org.springframework.cglib.core.CodeGenerationException;
import org.springframework.cglib.core.SpringNamingPolicy;
import org.springframework.cglib.proxy.Callback;
import org.springframework.cglib.proxy.CallbackFilter;
import org.springframework.cglib.proxy.Dispatcher;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.Factory;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;
import org.springframework.cglib.proxy.NoOp;
import org.springframework.cglib.transform.impl.UndeclaredThrowableStrategy;
import org.springframework.core.SmartClassLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;

/**
 * CGLIB-based {@link AopProxy} implementation for the Spring AOP framework.
 *
 * <p>Objects of this type should be obtained through proxy factories,
 * configured by an {@link AdvisedSupport} object. This class is internal
 * to Spring's AOP framework and need not be used directly by client code.
 *
 * <p>{@link DefaultAopProxyFactory} will automatically create CGLIB-based
 * proxies if necessary, for example in case of proxying a target class
 * (see the {@link DefaultAopProxyFactory attendant javadoc} for details).
 *
 * <p>Proxies created using this class are thread-safe if the underlying
 * (target) class is thread-safe.
 *
 * @author Rod Johnson
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Ramnivas Laddad
 * @author Chris Beams
 * @author Dave Syer
 * @see org.springframework.cglib.proxy.Enhancer
 * @see AdvisedSupport#setProxyTargetClass
 * @see DefaultAopProxyFactory
 */
@SuppressWarnings("serial")
class CglibAopProxy implements AopProxy, Serializable {

	// Constants for CGLIB callback array indices
	private static final int AOP_PROXY = 0;
	private static final int INVOKE_TARGET = 1;
	private static final int NO_OVERRIDE = 2;
	private static final int DISPATCH_TARGET = 3;
	private static final int DISPATCH_ADVISED = 4;
	private static final int INVOKE_EQUALS = 5;
	private static final int INVOKE_HASHCODE = 6;


	/** Logger available to subclasses; static to optimize serialization. */
	protected static final Log logger = LogFactory.getLog(CglibAopProxy.class);

	/** Keeps track of the Classes that we have validated for final methods. */
	private static final Map<Class<?>, Boolean> validatedClasses = new WeakHashMap<>();


	/** The configuration used to configure this proxy. */
	protected final AdvisedSupport advised;

	@Nullable
	protected Object[] constructorArgs;

	@Nullable
	protected Class<?>[] constructorArgTypes;

	/** Dispatcher used for methods on Advised. */
	private final transient AdvisedDispatcher advisedDispatcher;

	private transient Map<String, Integer> fixedInterceptorMap = Collections.emptyMap();

	private transient int fixedInterceptorOffset;


	/**
	 * Create a new CglibAopProxy for the given AOP configuration.
	 * @param config the AOP configuration as AdvisedSupport object
	 * @throws AopConfigException if the config is invalid. We try to throw an informative
	 * exception in this case, rather than let a mysterious failure happen later.
	 */
	public CglibAopProxy(AdvisedSupport config) throws AopConfigException {
		Assert.notNull(config, "AdvisedSupport must not be null");
		if (config.getAdvisors().length == 0 && config.getTargetSource() == AdvisedSupport.EMPTY_TARGET_SOURCE) {
			throw new AopConfigException("No advisors and no TargetSource specified");
		}
		this.advised = config;
		this.advisedDispatcher = new AdvisedDispatcher(this.advised);
	}

	/**
	 * Set constructor arguments to use for creating the proxy.
	 * @param constructorArgs the constructor argument values
	 * @param constructorArgTypes the constructor argument types
	 */
	public void setConstructorArguments(@Nullable Object[] constructorArgs, @Nullable Class<?>[] constructorArgTypes) {
		if (constructorArgs == null || constructorArgTypes == null) {
			throw new IllegalArgumentException("Both 'constructorArgs' and 'constructorArgTypes' need to be specified");
		}
		if (constructorArgs.length != constructorArgTypes.length) {
			throw new IllegalArgumentException("Number of 'constructorArgs' (" + constructorArgs.length +
					") must match number of 'constructorArgTypes' (" + constructorArgTypes.length + ")");
		}
		this.constructorArgs = constructorArgs;
		this.constructorArgTypes = constructorArgTypes;
	}


	@Override
	public Object getProxy() {
		return getProxy(null);
	}

	@Override
	public Object getProxy(@Nullable ClassLoader classLoader) {
		if (logger.isTraceEnabled()) {
			logger.trace("Creating CGLIB proxy: " + this.advised.getTargetSource());
		}

		try {
			Class<?> rootClass = this.advised.getTargetClass();
			Assert.state(rootClass != null, "Target class must be available for creating a CGLIB proxy");

			// 代理的父类.
			Class<?> proxySuperClass = rootClass;
			// 判断当前类是否是已经通过Cglib代理生成的类, 如果是的, 则获取其原始父类
			if (ClassUtils.isCglibProxyClass(rootClass)) {
				proxySuperClass = rootClass.getSuperclass();

				// 获取父类实现的接口, 并将其设置到需要代理的接口中
				Class<?>[] additionalInterfaces = rootClass.getInterfaces();
				for (Class<?> additionalInterface : additionalInterfaces) {
					this.advised.addInterface(additionalInterface);
				}
			}

			// Validate the class, writing log messages as necessary
			/*
			 * 对目标类进行检查(CglibAopProxy#doValidateClass()), 主要检查点有三个:
			 * 1. 目标方法不能使用final修饰;
			 * 2. 目标方法不能是private类型的;
			 * 3. 目标方法不能是包访问权限的;
			 * 这三个点满足任何一个, 当前方法就不能被代理, 此时该方法就会被略过
			 */
			validateClassIfNecessary(proxySuperClass, classLoader);

			// Configure CGLIB Enhancer...
			// 创建Enhancer对象, 并且设置ClassLoader
			Enhancer enhancer = createEnhancer();
			if (classLoader != null) {
				enhancer.setClassLoader(classLoader);
				if (classLoader instanceof SmartClassLoader &&
						((SmartClassLoader) classLoader).isClassReloadable(proxySuperClass)) {
					enhancer.setUseCache(false);
				}
			}
			enhancer.setSuperclass(proxySuperClass);
			// 为要生成的代理类增加SpringProxy, Advised, DecoratingProxy三个需要实现的接口.
			enhancer.setInterfaces(AopProxyUtils.completeProxiedInterfaces(this.advised));
			enhancer.setNamingPolicy(SpringNamingPolicy.INSTANCE);
			enhancer.setStrategy(new ClassLoaderAwareUndeclaredThrowableStrategy(classLoader));

			// * 获取CallBack数组: 当前需要织入到代理类中的逻辑.
			Callback[] callbacks = getCallbacks(rootClass);
			Class<?>[] types = new Class<?>[callbacks.length];
			for (int x = 0; x < types.length; x++) {
				types[x] = callbacks[x].getClass();
			}

			// fixedInterceptorMap only populated at this point, after getCallbacks call above
			// * 设置代理类中各个方法将要使用的切面逻辑, 这里ProxyCallbackFilter.accept()方法返回
			// 的整型值正好一一对应上面Callback数组中各个切面逻辑的下标, 也就是说这里的CallbackFilter
			// 的作用正好指定了代理类中各个方法将要使用Callback数组中的哪个或哪几个切面逻辑.
			enhancer.setCallbackFilter(new ProxyCallbackFilter(
					this.advised.getConfigurationOnlyCopy(), this.fixedInterceptorMap, this.fixedInterceptorOffset));
			enhancer.setCallbackTypes(types);

			// Generate the proxy class and create a proxy instance.
			// 生成代理对象.
			return createProxyClassAndInstance(enhancer, callbacks);
		}
		catch (CodeGenerationException | IllegalArgumentException ex) {
			throw new AopConfigException("Could not generate CGLIB subclass of " + this.advised.getTargetClass() +
					": Common causes of this problem include using a final class or a non-visible class",
					ex);
		}
		catch (Throwable ex) {
			// TargetSource.getTarget() failed
			throw new AopConfigException("Unexpected AOP exception", ex);
		}
	}

	protected Object createProxyClassAndInstance(Enhancer enhancer, Callback[] callbacks) {
		enhancer.setInterceptDuringConstruction(false);
		enhancer.setCallbacks(callbacks);
		return (this.constructorArgs != null && this.constructorArgTypes != null ?
				enhancer.create(this.constructorArgTypes, this.constructorArgs) :
				enhancer.create());
	}

	/**
	 * Creates the CGLIB {@link Enhancer}. Subclasses may wish to override this to return a custom
	 * {@link Enhancer} implementation.
	 */
	protected Enhancer createEnhancer() {
		return new Enhancer();
	}

	/**
	 * Checks to see whether the supplied {@code Class} has already been validated and
	 * validates it if not.
	 */
	private void validateClassIfNecessary(Class<?> proxySuperClass, @Nullable ClassLoader proxyClassLoader) {
		if (logger.isWarnEnabled()) {
			synchronized (validatedClasses) {
				if (!validatedClasses.containsKey(proxySuperClass)) {
					doValidateClass(proxySuperClass, proxyClassLoader,
							ClassUtils.getAllInterfacesForClassAsSet(proxySuperClass));
					validatedClasses.put(proxySuperClass, Boolean.TRUE);
				}
			}
		}
	}

	/**
	 * Checks for final methods on the given {@code Class}, as well as package-visible
	 * methods across ClassLoaders, and writes warnings to the log for each one found.
	 */
	private void doValidateClass(Class<?> proxySuperClass, @Nullable ClassLoader proxyClassLoader, Set<Class<?>> ifcs) {
		if (proxySuperClass != Object.class) {
			Method[] methods = proxySuperClass.getDeclaredMethods();
			for (Method method : methods) {
				int mod = method.getModifiers();
				if (!Modifier.isStatic(mod) && !Modifier.isPrivate(mod)) {
					if (Modifier.isFinal(mod)) {
						if (implementsInterface(method, ifcs)) {
							logger.info("Unable to proxy interface-implementing method [" + method + "] because " +
									"it is marked as final: Consider using interface-based JDK proxies instead!");
						}
						logger.debug("Final method [" + method + "] cannot get proxied via CGLIB: " +
								"Calls to this method will NOT be routed to the target instance and " +
								"might lead to NPEs against uninitialized fields in the proxy instance.");
					}
					else if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod) &&
							proxyClassLoader != null && proxySuperClass.getClassLoader() != proxyClassLoader) {
						logger.debug("Method [" + method + "] is package-visible across different ClassLoaders " +
								"and cannot get proxied via CGLIB: Declare this method as public or protected " +
								"if you need to support invocations through the proxy.");
					}
				}
			}
			doValidateClass(proxySuperClass.getSuperclass(), proxyClassLoader, ifcs);
		}
	}

	/**
	 * <ol>
	 *     getCallbacks 的主要执行过程
	 * <li> 获取目标对象的动态调用链
	 * <li> 判断是否设置了exposeProxy属性, 如果设置了, 则生成一个可以暴露代理对象的Callback对象,
	 *  否则生成一个不做任何处理直接调用目标对象的Callback对象
	 * <li> 判断是否设置了exposeProxy属性, 如果设置了, 则生成一个可以暴露代理对象的Callback对象,
	 *  否则生成一个不做任何处理直接调用目标对象的Callback对象
	 * <li>
	 * </ol>
	 */
	private Callback[] getCallbacks(Class<?> rootClass) throws Exception {
		// Parameters used for optimization choices...
		boolean exposeProxy = this.advised.isExposeProxy();
		boolean isFrozen = this.advised.isFrozen();
		boolean isStatic = this.advised.getTargetSource().isStatic();

		// Choose an "aop" interceptor (used for AOP calls).
		// 用户自定义的代理逻辑的主要织入类.
		Callback aopInterceptor = new DynamicAdvisedInterceptor(this.advised);

		// Choose a "straight to target" interceptor. (used for calls that are
		// unadvised but can return this). May be required to expose the proxy.
		// 判断如果要暴露代理对象, 如果是, 则使用AopContext设置将代理对象设置到ThreadLocal中,
		// 这样用户则可以通过AopContext获取目标对象.
		Callback targetInterceptor;
		if (exposeProxy) {
			// 判断被代理的对象是否是静态的, 如果是静态的, 则将目标对象缓存起来, 每次都使用该对象即可, 如果目标对象是动态的,
			// 则在DynamicUnadvisedExposedInterceptor中每次都生成一个新的目标对象, 以织入后面的代理逻辑.
			targetInterceptor = (isStatic ?
					new StaticUnadvisedExposedInterceptor(this.advised.getTargetSource().getTarget()) :
					new DynamicUnadvisedExposedInterceptor(this.advised.getTargetSource()));
		}
		else {
			// 下面两个类与上面两个的唯一区别就在于是否使用AopContext暴露生成的代理对象.
			targetInterceptor = (isStatic ?
					new StaticUnadvisedInterceptor(this.advised.getTargetSource().getTarget()) :
					new DynamicUnadvisedInterceptor(this.advised.getTargetSource()));
		}

		// Choose a "direct to target" dispatcher (used for
		// unadvised calls to static targets that cannot return this).
		// 当前Callback用于一般的不用被代理的方法, 这些方法.
		Callback targetDispatcher = (isStatic ?
				new StaticDispatcher(this.advised.getTargetSource().getTarget()) : new SerializableNoOp());

		// 将获取到的callback组装为一个数组
		Callback[] mainCallbacks = new Callback[] {
				aopInterceptor,  // for normal advice 用户自己定义的拦截器
				targetInterceptor,  // invoke target without considering advice, if optimized 根据条件是否暴露代理对象的拦截器
				new SerializableNoOp(),  // no override for methods mapped to this 不做任何操作的拦截器
				targetDispatcher, this.advisedDispatcher, // 用于存储Advised对象的分发器
				new EqualsInterceptor(this.advised), // 针对equals方法调用的拦截器
				new HashCodeInterceptor(this.advised) // 针对hashcode方法调用的拦截器
		};

		Callback[] callbacks;

		// If the target is a static one and the advice chain is frozen,
		// then we can make some optimizations by sending the AOP calls
		// direct to the target using the fixed chain for that method.
		// 如果目标对象是静态的, 也即可以缓存的, 并且切面逻辑的调用链是固定的, 则对目标对象和整个调用链进行缓存.
		if (isStatic && isFrozen) {
			Method[] methods = rootClass.getMethods();
			Callback[] fixedCallbacks = new Callback[methods.length];
			this.fixedInterceptorMap = new HashMap<>(methods.length);

			// TODO: small memory optimization here (can skip creation for methods with no advice)
			// 遍历所有目标类的方法.
			for (int x = 0; x < methods.length; x++) {
				// 获取目标对象的切面逻辑
				List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(methods[x], rootClass);
				fixedCallbacks[x] = new FixedChainStaticTargetInterceptor(
						chain, this.advised.getTargetSource().getTarget(), this.advised.getTargetClass());
				// 对调用链进行缓存
				this.fixedInterceptorMap.put(methods[x].toString(), x);
			}

			// Now copy both the callbacks from mainCallbacks
			// and fixedCallbacks into the callbacks array.
			// 将生成的静态调用链存入Callback数组中
			callbacks = new Callback[mainCallbacks.length + fixedCallbacks.length];
			System.arraycopy(mainCallbacks, 0, callbacks, 0, mainCallbacks.length);
			System.arraycopy(fixedCallbacks, 0, callbacks, mainCallbacks.length, fixedCallbacks.length);

			// 这里fixedInterceptorOffset记录了当前静态的调用链的切面逻辑的起始位置,
			// 这里记录的用处在于后面使用CallbackFilter的时候, 如果发现是静态的调用链,
			// 则直接通过该参数获取相应的调用链, 而直接略过了前面的动态调用链.
			this.fixedInterceptorOffset = mainCallbacks.length;
		}
		else {
			callbacks = mainCallbacks;
		}
		return callbacks;
	}


	@Override
	public boolean equals(Object other) {
		return (this == other || (other instanceof CglibAopProxy &&
				AopProxyUtils.equalsInProxy(this.advised, ((CglibAopProxy) other).advised)));
	}

	@Override
	public int hashCode() {
		return CglibAopProxy.class.hashCode() * 13 + this.advised.getTargetSource().hashCode();
	}


	/**
	 * Check whether the given method is declared on any of the given interfaces.
	 */
	private static boolean implementsInterface(Method method, Set<Class<?>> ifcs) {
		for (Class<?> ifc : ifcs) {
			if (ClassUtils.hasMethod(ifc, method.getName(), method.getParameterTypes())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Process a return value. Wraps a return of {@code this} if necessary to be the
	 * {@code proxy} and also verifies that {@code null} is not returned as a primitive.
	 *
	 * <p> 对返回值进行处理, 如果返回值就是当前目标对象, 那么将代理生成的代理对象返回;
	 *  如果返回值为空, 并且返回值类型是非void的基本数据类型, 则抛出异常;
	 *  如果上述两个条件都不符合, 则直接将生成的返回值返回.
	 */
	@Nullable
	private static Object processReturnType(
			Object proxy, @Nullable Object target, Method method, @Nullable Object returnValue) {

		// Massage return value if necessary
		if (returnValue != null && returnValue == target &&
				!RawTargetAccess.class.isAssignableFrom(method.getDeclaringClass())) {
			// Special case: it returned "this". Note that we can't help
			// if the target sets a reference to itself in another returned object.
			returnValue = proxy;
		}
		Class<?> returnType = method.getReturnType();
		if (returnValue == null && returnType != Void.TYPE && returnType.isPrimitive()) {
			throw new AopInvocationException(
					"Null return value from advice does not match primitive return type for: " + method);
		}
		return returnValue;
	}


	/**
	 * Serializable replacement for CGLIB's NoOp interface.
	 * Public to allow use elsewhere in the framework.
	 */
	public static class SerializableNoOp implements NoOp, Serializable {
	}


	/**
	 * Method interceptor used for static targets with no advice chain. The call
	 * is passed directly back to the target. Used when the proxy needs to be
	 * exposed and it can't be determined that the method won't return
	 * {@code this}.
	 */
	private static class StaticUnadvisedInterceptor implements MethodInterceptor, Serializable {

		@Nullable
		private final Object target;

		public StaticUnadvisedInterceptor(@Nullable Object target) {
			this.target = target;
		}

		@Override
		@Nullable
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
			Object retVal = methodProxy.invoke(this.target, args);
			return processReturnType(proxy, this.target, method, retVal);
		}
	}


	/**
	 * Method interceptor used for static targets with no advice chain, when the
	 * proxy is to be exposed.
	 */
	private static class StaticUnadvisedExposedInterceptor implements MethodInterceptor, Serializable {

		@Nullable
		private final Object target;

		public StaticUnadvisedExposedInterceptor(@Nullable Object target) {
			this.target = target;
		}

		@Override
		@Nullable
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
			Object oldProxy = null;
			try {
				oldProxy = AopContext.setCurrentProxy(proxy);
				Object retVal = methodProxy.invoke(this.target, args);
				return processReturnType(proxy, this.target, method, retVal);
			}
			finally {
				AopContext.setCurrentProxy(oldProxy);
			}
		}
	}


	/**
	 * Interceptor used to invoke a dynamic target without creating a method
	 * invocation or evaluating an advice chain. (We know there was no advice
	 * for this method.)
	 */
	private static class DynamicUnadvisedInterceptor implements MethodInterceptor, Serializable {

		private final TargetSource targetSource;

		public DynamicUnadvisedInterceptor(TargetSource targetSource) {
			this.targetSource = targetSource;
		}

		@Override
		@Nullable
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
			Object target = this.targetSource.getTarget();
			try {
				Object retVal = methodProxy.invoke(target, args);
				return processReturnType(proxy, target, method, retVal);
			}
			finally {
				if (target != null) {
					this.targetSource.releaseTarget(target);
				}
			}
		}
	}


	/**
	 * Interceptor for unadvised dynamic targets when the proxy needs exposing.
	 */
	private static class DynamicUnadvisedExposedInterceptor implements MethodInterceptor, Serializable {

		private final TargetSource targetSource;

		public DynamicUnadvisedExposedInterceptor(TargetSource targetSource) {
			this.targetSource = targetSource;
		}

		@Override
		@Nullable
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
			Object oldProxy = null;
			Object target = this.targetSource.getTarget();
			try {
				oldProxy = AopContext.setCurrentProxy(proxy);
				Object retVal = methodProxy.invoke(target, args);
				return processReturnType(proxy, target, method, retVal);
			}
			finally {
				AopContext.setCurrentProxy(oldProxy);
				if (target != null) {
					this.targetSource.releaseTarget(target);
				}
			}
		}
	}


	/**
	 * Dispatcher for a static target. Dispatcher is much faster than
	 * interceptor. This will be used whenever it can be determined that a
	 * method definitely does not return "this"
	 */
	private static class StaticDispatcher implements Dispatcher, Serializable {

		@Nullable
		private Object target;

		public StaticDispatcher(@Nullable Object target) {
			this.target = target;
		}

		@Override
		@Nullable
		public Object loadObject() {
			return this.target;
		}
	}


	/**
	 * Dispatcher for any methods declared on the Advised class.
	 */
	private static class AdvisedDispatcher implements Dispatcher, Serializable {

		private final AdvisedSupport advised;

		public AdvisedDispatcher(AdvisedSupport advised) {
			this.advised = advised;
		}

		@Override
		public Object loadObject() throws Exception {
			return this.advised;
		}
	}


	/**
	 * Dispatcher for the {@code equals} method.
	 * Ensures that the method call is always handled by this class.
	 */
	private static class EqualsInterceptor implements MethodInterceptor, Serializable {

		private final AdvisedSupport advised;

		public EqualsInterceptor(AdvisedSupport advised) {
			this.advised = advised;
		}

		@Override
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) {
			Object other = args[0];
			if (proxy == other) {
				return true;
			}
			if (other instanceof Factory) {
				Callback callback = ((Factory) other).getCallback(INVOKE_EQUALS);
				if (!(callback instanceof EqualsInterceptor)) {
					return false;
				}
				AdvisedSupport otherAdvised = ((EqualsInterceptor) callback).advised;
				return AopProxyUtils.equalsInProxy(this.advised, otherAdvised);
			}
			else {
				return false;
			}
		}
	}


	/**
	 * Dispatcher for the {@code hashCode} method.
	 * Ensures that the method call is always handled by this class.
	 */
	private static class HashCodeInterceptor implements MethodInterceptor, Serializable {

		private final AdvisedSupport advised;

		public HashCodeInterceptor(AdvisedSupport advised) {
			this.advised = advised;
		}

		@Override
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) {
			return CglibAopProxy.class.hashCode() * 13 + this.advised.getTargetSource().hashCode();
		}
	}


	/**
	 * Interceptor used specifically for advised methods on a frozen, static proxy.
	 */
	private static class FixedChainStaticTargetInterceptor implements MethodInterceptor, Serializable {

		private final List<Object> adviceChain;

		@Nullable
		private final Object target;

		@Nullable
		private final Class<?> targetClass;

		public FixedChainStaticTargetInterceptor(
				List<Object> adviceChain, @Nullable Object target, @Nullable Class<?> targetClass) {

			this.adviceChain = adviceChain;
			this.target = target;
			this.targetClass = targetClass;
		}

		@Override
		@Nullable
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
			MethodInvocation invocation = new CglibMethodInvocation(proxy, this.target, method, args,
					this.targetClass, this.adviceChain, methodProxy);
			// If we get here, we need to create a MethodInvocation.
			Object retVal = invocation.proceed();
			retVal = processReturnType(proxy, this.target, method, retVal);
			return retVal;
		}
	}


	/**
	 * General purpose AOP callback. Used when the target is dynamic or when the
	 * proxy is not frozen.
	 */
	private static class DynamicAdvisedInterceptor implements MethodInterceptor, Serializable {

		private final AdvisedSupport advised;

		public DynamicAdvisedInterceptor(AdvisedSupport advised) {
			this.advised = advised;
		}

		@Override
		@Nullable
		public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
			Object oldProxy = null;
			boolean setProxyContext = false;
			Object target = null;
			// 通过TargetSource获取目标对象
			TargetSource targetSource = this.advised.getTargetSource();
			try {
				// 判断如果需要暴露代理对象, 则将当前代理对象设置到ThreadLocal中
				if (this.advised.exposeProxy) {
					// Make invocation available if necessary.
					oldProxy = AopContext.setCurrentProxy(proxy);
					setProxyContext = true;
				}
				// Get as late as possible to minimize the time we "own" the target, in case it comes from a pool...
				target = targetSource.getTarget();
				Class<?> targetClass = (target != null ? target.getClass() : null);
				// 获取目标对象切面逻辑的拦截器链
				List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass);
				Object retVal;
				// Check whether we only have one InvokerInterceptor: that is,
				// no real advice, but just reflective invocation of the target.
				if (chain.isEmpty() && Modifier.isPublic(method.getModifiers())) {
					// We can skip creating a MethodInvocation: just invoke the target directly.
					// Note that the final invoker must be an InvokerInterceptor, so we know
					// it does nothing but a reflective operation on the target, and no hot
					// swapping or fancy proxying.
					// 对参数进行处理, 以使其与目标方法的参数类型一致, 尤其对于数组类型, 会单独处理其数据类型与实际类型一致.
					Object[] argsToUse = AopProxyUtils.adaptArgumentsIfNecessary(method, args);
					// 直接调用目标方法
					retVal = methodProxy.invoke(target, argsToUse);
				}
				else {
					// We need to create a method invocation...
					// 通过生成的调用链, 对目标方法进行环绕调用.
					retVal = new CglibMethodInvocation(proxy, target, method, args, targetClass, chain, methodProxy).proceed();
				}

				// 处理返回值.
				retVal = processReturnType(proxy, target, method, retVal);
				return retVal;
			}
			finally {
				// 如果目标对象不是静态的, 则调用TargetSource.releaseTarget()方法释放目标对象.
				if (target != null && !targetSource.isStatic()) {
					targetSource.releaseTarget(target);
				}

				// 将代理对象设置为前面(外层逻辑)调用设置的对象, 以防止暴露出来的代理对象不一致.
				if (setProxyContext) {
					// 重置线程变量为旧的代理类.
					// Restore old proxy.
					AopContext.setCurrentProxy(oldProxy);
				}
			}
		}

		@Override
		public boolean equals(Object other) {
			return (this == other ||
					(other instanceof DynamicAdvisedInterceptor &&
							this.advised.equals(((DynamicAdvisedInterceptor) other).advised)));
		}

		/**
		 * CGLIB uses this to drive proxy creation.
		 */
		@Override
		public int hashCode() {
			return this.advised.hashCode();
		}
	}


	/**
	 * Implementation of AOP Alliance MethodInvocation used by this AOP proxy.
	 */
	private static class CglibMethodInvocation extends ReflectiveMethodInvocation {

		private final MethodProxy methodProxy;

		private final boolean publicMethod;

		public CglibMethodInvocation(Object proxy, @Nullable Object target, Method method,
				Object[] arguments, @Nullable Class<?> targetClass,
				List<Object> interceptorsAndDynamicMethodMatchers, MethodProxy methodProxy) {

			super(proxy, target, method, arguments, targetClass, interceptorsAndDynamicMethodMatchers);
			this.methodProxy = methodProxy;
			this.publicMethod = Modifier.isPublic(method.getModifiers());
		}

		/**
		 * Gives a marginal performance improvement versus using reflection to
		 * invoke the target when invoking public methods.
		 */
		@Override
		protected Object invokeJoinpoint() throws Throwable {
			if (this.publicMethod && getMethod().getDeclaringClass() != Object.class) {
				return this.methodProxy.invoke(this.target, this.arguments);
			}
			else {
				return super.invokeJoinpoint();
			}
		}
	}


	/**
	 * CallbackFilter to assign Callbacks to methods.
	 */
	private static class ProxyCallbackFilter implements CallbackFilter {

		private final AdvisedSupport advised;

		private final Map<String, Integer> fixedInterceptorMap;

		private final int fixedInterceptorOffset;

		public ProxyCallbackFilter(
				AdvisedSupport advised, Map<String, Integer> fixedInterceptorMap, int fixedInterceptorOffset) {

			this.advised = advised;
			this.fixedInterceptorMap = fixedInterceptorMap;
			this.fixedInterceptorOffset = fixedInterceptorOffset;
		}

		/**
		 * Implementation of CallbackFilter.accept() to return the index of the
		 * callback we need.
		 * <p>The callbacks for each proxy are built up of a set of fixed callbacks
		 * for general use and then a set of callbacks that are specific to a method
		 * for use on static targets with a fixed advice chain.
		 * <p>The callback used is determined thus:
		 * <dl>
		 * <dt>For exposed proxies</dt>
		 * <dd>Exposing the proxy requires code to execute before and after the
		 * method/chain invocation. This means we must use
		 * DynamicAdvisedInterceptor, since all other interceptors can avoid the
		 * need for a try/catch block</dd>
		 * <dt>For Object.finalize():</dt>
		 * <dd>No override for this method is used.</dd>
		 * <dt>For equals():</dt>
		 * <dd>The EqualsInterceptor is used to redirect equals() calls to a
		 * special handler to this proxy.</dd>
		 * <dt>For methods on the Advised class:</dt>
		 * <dd>the AdvisedDispatcher is used to dispatch the call directly to
		 * the target</dd>
		 * <dt>For advised methods:</dt>
		 * <dd>If the target is static and the advice chain is frozen then a
		 * FixedChainStaticTargetInterceptor specific to the method is used to
		 * invoke the advice chain. Otherwise a DyanmicAdvisedInterceptor is
		 * used.</dd>
		 * <dt>For non-advised methods:</dt>
		 * <dd>Where it can be determined that the method will not return {@code this}
		 * or when {@code ProxyFactory.getExposeProxy()} returns {@code false},
		 * then a Dispatcher is used. For static targets, the StaticDispatcher is used;
		 * and for dynamic targets, a DynamicUnadvisedInterceptor is used.
		 * If it possible for the method to return {@code this} then a
		 * StaticUnadvisedInterceptor is used for static targets - the
		 * DynamicUnadvisedInterceptor already considers this.</dd>
		 * </dl>
		 *
		 * <p> CallbackFilter.accept()方法接收一个Method类型的参数, 该参数也即当前要生成的代理逻辑的方法,
		 * 这里的accept()方法将返回目标当前要织入代理逻辑的方法所需要使用的切面逻辑, 也即Callback对象在Callback数组中的下标.
		 *
		 */
		@Override
		public int accept(Method method) {
			if (AopUtils.isFinalizeMethod(method)) {
				logger.trace("Found finalize() method - using NO_OVERRIDE");
				return NO_OVERRIDE;
			}
			if (!this.advised.isOpaque() && method.getDeclaringClass().isInterface() &&
					method.getDeclaringClass().isAssignableFrom(Advised.class)) {
				if (logger.isTraceEnabled()) {
					logger.trace("Method is declared on Advised interface: " + method);
				}
				return DISPATCH_ADVISED;
			}
			// We must always proxy equals, to direct calls to this.
			if (AopUtils.isEqualsMethod(method)) {
				if (logger.isTraceEnabled()) {
					logger.trace("Found 'equals' method: " + method);
				}
				return INVOKE_EQUALS;
			}
			// We must always calculate hashCode based on the proxy.
			if (AopUtils.isHashCodeMethod(method)) {
				if (logger.isTraceEnabled()) {
					logger.trace("Found 'hashCode' method: " + method);
				}
				return INVOKE_HASHCODE;
			}
			Class<?> targetClass = this.advised.getTargetClass();
			// Proxy is not yet available, but that shouldn't matter.
			List<?> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass);
			boolean haveAdvice = !chain.isEmpty();
			boolean exposeProxy = this.advised.isExposeProxy();
			boolean isStatic = this.advised.getTargetSource().isStatic();
			boolean isFrozen = this.advised.isFrozen();
			if (haveAdvice || !isFrozen) {
				// If exposing the proxy, then AOP_PROXY must be used.
				if (exposeProxy) {
					if (logger.isTraceEnabled()) {
						logger.trace("Must expose proxy on advised method: " + method);
					}
					return AOP_PROXY;
				}
				String key = method.toString();
				// Check to see if we have fixed interceptor to serve this method.
				// Else use the AOP_PROXY.
				if (isStatic && isFrozen && this.fixedInterceptorMap.containsKey(key)) {
					if (logger.isTraceEnabled()) {
						logger.trace("Method has advice and optimizations are enabled: " + method);
					}
					// We know that we are optimizing so we can use the FixedStaticChainInterceptors.
					int index = this.fixedInterceptorMap.get(key);
					return (index + this.fixedInterceptorOffset);
				}
				else {
					if (logger.isTraceEnabled()) {
						logger.trace("Unable to apply any optimizations to advised method: " + method);
					}
					return AOP_PROXY;
				}
			}
			else {
				// See if the return type of the method is outside the class hierarchy of the target type.
				// If so we know it never needs to have return type massage and can use a dispatcher.
				// If the proxy is being exposed, then must use the interceptor the correct one is already
				// configured. If the target is not static, then we cannot use a dispatcher because the
				// target needs to be explicitly released after the invocation.
				if (exposeProxy || !isStatic) {
					return INVOKE_TARGET;
				}
				Class<?> returnType = method.getReturnType();
				if (targetClass != null && returnType.isAssignableFrom(targetClass)) {
					if (logger.isTraceEnabled()) {
						logger.trace("Method return type is assignable from target type and " +
								"may therefore return 'this' - using INVOKE_TARGET: " + method);
					}
					return INVOKE_TARGET;
				}
				else {
					if (logger.isTraceEnabled()) {
						logger.trace("Method return type ensures 'this' cannot be returned - " +
								"using DISPATCH_TARGET: " + method);
					}
					return DISPATCH_TARGET;
				}
			}
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof ProxyCallbackFilter)) {
				return false;
			}
			ProxyCallbackFilter otherCallbackFilter = (ProxyCallbackFilter) other;
			AdvisedSupport otherAdvised = otherCallbackFilter.advised;
			if (this.advised.isFrozen() != otherAdvised.isFrozen()) {
				return false;
			}
			if (this.advised.isExposeProxy() != otherAdvised.isExposeProxy()) {
				return false;
			}
			if (this.advised.getTargetSource().isStatic() != otherAdvised.getTargetSource().isStatic()) {
				return false;
			}
			if (!AopProxyUtils.equalsProxiedInterfaces(this.advised, otherAdvised)) {
				return false;
			}
			// Advice instance identity is unimportant to the proxy class:
			// All that matters is type and ordering.
			Advisor[] thisAdvisors = this.advised.getAdvisors();
			Advisor[] thatAdvisors = otherAdvised.getAdvisors();
			if (thisAdvisors.length != thatAdvisors.length) {
				return false;
			}
			for (int i = 0; i < thisAdvisors.length; i++) {
				Advisor thisAdvisor = thisAdvisors[i];
				Advisor thatAdvisor = thatAdvisors[i];
				if (!equalsAdviceClasses(thisAdvisor, thatAdvisor)) {
					return false;
				}
				if (!equalsPointcuts(thisAdvisor, thatAdvisor)) {
					return false;
				}
			}
			return true;
		}

		private boolean equalsAdviceClasses(Advisor a, Advisor b) {
			return (a.getAdvice().getClass() == b.getAdvice().getClass());
		}

		private boolean equalsPointcuts(Advisor a, Advisor b) {
			// If only one of the advisor (but not both) is PointcutAdvisor, then it is a mismatch.
			// Takes care of the situations where an IntroductionAdvisor is used (see SPR-3959).
			return (!(a instanceof PointcutAdvisor) ||
					(b instanceof PointcutAdvisor &&
							ObjectUtils.nullSafeEquals(((PointcutAdvisor) a).getPointcut(), ((PointcutAdvisor) b).getPointcut())));
		}

		@Override
		public int hashCode() {
			int hashCode = 0;
			Advisor[] advisors = this.advised.getAdvisors();
			for (Advisor advisor : advisors) {
				Advice advice = advisor.getAdvice();
				hashCode = 13 * hashCode + advice.getClass().hashCode();
			}
			hashCode = 13 * hashCode + (this.advised.isFrozen() ? 1 : 0);
			hashCode = 13 * hashCode + (this.advised.isExposeProxy() ? 1 : 0);
			hashCode = 13 * hashCode + (this.advised.isOptimize() ? 1 : 0);
			hashCode = 13 * hashCode + (this.advised.isOpaque() ? 1 : 0);
			return hashCode;
		}
	}


	/**
	 * CGLIB GeneratorStrategy variant which exposes the application ClassLoader
	 * as thread context ClassLoader for the time of class generation
	 * (in order for ASM to pick it up when doing common superclass resolution).
	 */
	private static class ClassLoaderAwareUndeclaredThrowableStrategy extends UndeclaredThrowableStrategy {

		@Nullable
		private final ClassLoader classLoader;

		public ClassLoaderAwareUndeclaredThrowableStrategy(@Nullable ClassLoader classLoader) {
			super(UndeclaredThrowableException.class);
			this.classLoader = classLoader;
		}

		@Override
		public byte[] generate(ClassGenerator cg) throws Exception {
			if (this.classLoader == null) {
				return super.generate(cg);
			}

			Thread currentThread = Thread.currentThread();
			ClassLoader threadContextClassLoader;
			try {
				threadContextClassLoader = currentThread.getContextClassLoader();
			}
			catch (Throwable ex) {
				// Cannot access thread context ClassLoader - falling back...
				return super.generate(cg);
			}

			boolean overrideClassLoader = !this.classLoader.equals(threadContextClassLoader);
			if (overrideClassLoader) {
				currentThread.setContextClassLoader(this.classLoader);
			}
			try {
				return super.generate(cg);
			}
			finally {
				if (overrideClassLoader) {
					// Reset original thread context ClassLoader.
					currentThread.setContextClassLoader(threadContextClassLoader);
				}
			}
		}
	}

}
