package com.cobostack.spring.ioc.service;

import com.cobostack.spring.ioc.dao.CustomerDao;

/**
 * @author Xu_Ming
 * @version 1.0, 2018/9/26
 */

public class CustomerServiceImpl implements CustomerService {

	private CustomerDao customerDao;

	@Override
	public void save() {
		customerDao.save();
		// 异常通知测试
		// int i = 1 / 0;
	}

	@Override
	public void select() {
		customerDao.select();
	}

	@Override
	public int delete(int id) {
		return customerDao.delete(id);
	}

	public void setCustomerDao(CustomerDao customerDao) {
		this.customerDao = customerDao;
	}
}