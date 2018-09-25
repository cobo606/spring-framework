package com.cobostack.spring.ioc;

import com.cobostack.spring.ioc.service.CustomerService;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Spring IOC
 *
 * @author Xu_Ming
 * @version 1.0, 2018/9/26
 */

public class Application {

	public static void main(String[] args) {

		ClassPathXmlApplicationContext applicationContext = new ClassPathXmlApplicationContext("ioc.xml");
		CustomerService customerService = (CustomerService) applicationContext.getBean("customerService");
		customerService.save();
	}
}