package com.cobostack.spring.ioc.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * @author Xu_Ming
 * @version 1.0, 2018/9/26
 */

public class CustomerDaoImpl implements CustomerDao, InitializingBean, DisposableBean {

	/** logger */
	private static final Logger LOGGER = LoggerFactory.getLogger(CustomerDaoImpl.class);

	public void init() {
		LOGGER.debug("[{}] invoke init-method", this);
	}

	public CustomerDaoImpl() {
		LOGGER.debug("[{}] invoke Construct", this);
	}

	@Override
	public void save() {
		LOGGER.debug("[{}] invoke save", this);
	}

	// 销毁方法
	@Override
	public void destroy() throws Exception {
		LOGGER.debug("[{}] invoke destroy", this);
	}

	// bean 创建成功后调用, 优先于 init-method
	@Override
	public void afterPropertiesSet() throws Exception {
		LOGGER.debug("[{}] invoke afterPropertiesSet", this);
	}
}