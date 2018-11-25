package com.cobostack.spring;

import com.cobostack.spring.ioc.service.CustomerService;
import org.springframework.cglib.core.DebuggingClassWriter;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Spring IOC
 *
 * @author Xu_Ming
 * @version 1.0, 2018/9/26
 */

public class Application {

	public static void main(String[] args) {
		/// 生成动态代理对象 Proxy0...
		// System.setProperty("sun.misc.ProxyGenerator.saveGeneratedFiles", "true");
		// System.setProperty(DebuggingClassWriter.DEBUG_LOCATION_PROPERTY, "cglib-class");

		ClassPathXmlApplicationContext applicationContext =
				new ClassPathXmlApplicationContext("ioc.xml", "aop.xml");
		CustomerService customerService = (CustomerService) applicationContext.getBean("customerService");
		customerService.save();
		customerService.select();
		customerService.delete(1);
	}
}