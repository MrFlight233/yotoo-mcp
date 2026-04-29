package com.yotoo.mcp.bean;

import lombok.Data;

/**
 * skill_api_param.
 * API参数表.
 *
 * @author cheng yu qin
 */
@Data
public class ApiParam {
	private Integer paramId; //参数主键
	private Integer apiEdition; //API版本（只查询值为2的数据）
	private Integer apiId; //API主键
	private String paramName; //参数名
	private String paramDataType; //类型（string；number；integer；boolean；array；object；）
	private String required; //是否必填（true；false；）
	private String paramDescription; //参数描述
	private String paramEnum; //枚举值（“,”间隔）
	private Integer multipleFlag; //是否多选（0否；1是；）
	private String systemField; //自动填充（系统信息）
	private String testValue; //测试值
	private Integer confirmationRequired; //是否需要用户确认（0否；1是；）
}
