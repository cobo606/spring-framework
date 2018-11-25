package com.cobostack.spring.ioc.service;

/**
 * @author Xu_Ming
 * @version 1.0, 2018/9/26
 */

public interface CustomerService {

	void save();

	void select();

	int delete(int id);
}