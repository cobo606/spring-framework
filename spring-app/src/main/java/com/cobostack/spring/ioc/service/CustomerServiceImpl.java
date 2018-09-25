package com.cobostack.spring.ioc.service;

import com.cobostack.spring.ioc.dao.CustomerDao;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author Xu_Ming
 * @version 1.0, 2018/9/26
 */

public class CustomerServiceImpl implements CustomerService {

	@Autowired
	private CustomerDao customerDao;

	@Override
	public void save() {
		customerDao.save();
	}
}