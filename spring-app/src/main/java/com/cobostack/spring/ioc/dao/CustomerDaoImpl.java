package com.cobostack.spring.ioc.dao;

/**
 * @author Xu_Ming
 * @version 1.0, 2018/9/26
 */

public class CustomerDaoImpl implements CustomerDao {

	@Override
	public void save() {
		System.out.println("CustomerDao Save!");
	}
}