package com.cobostack.spring.ioc.dao;

/**
 * @author Xu_Ming
 * @version 1.0, 2018/9/26
 */

public interface CustomerDao {

	void save();

	void select();

	int delete(int id);
}