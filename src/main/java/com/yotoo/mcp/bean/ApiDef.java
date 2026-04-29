package com.yotoo.mcp.bean;

import java.time.OffsetDateTime;
import java.util.List;

import lombok.Data;

/**
 * skill_api_def.
 * API定义表.
 *
 * @author cheng yu qin
 */
@Data
public class ApiDef {
	private Integer apiId; //API主键（与草稿表ID相同）
	private String apiName; //名称
	private String operationId; //标识
	private String tagIds; //标签
	private Integer openFlag; //是否公开（0否；1是；）
	private Integer apiType; //接口类型（1数据查询；2表单提交；）
	private Integer configOpenFlag; //技能配置公开（0否；1是；）
	private Integer classifiedFlag; //是否涉密（0不涉密；1涉密；）
	private String summary; //描述
	private String apiPath; //接口地址
	private String requestWay; //请求方式（get；post；patch；delete；）
	private Integer authType; //鉴权方式（1No Auth；2API Key；3Bearer Token；）
	private String authValue; //鉴权值
	private String replyPromptWord; //回复提示词
	private String apiResponse; //接口返回
	private String modelResponse; //模型返回
	private Integer createUserId; //创建人
	private OffsetDateTime createTime; //创建时间
	private OffsetDateTime updateTime; //更新时间
	private Integer collegeId; //系部ID
	private Integer majorId; //专业ID
	private Integer classId; //班级ID
	private String roleIds; //角色IDS
	private Integer releaseStatus; //上架状态（0草稿；1上架）
	private Long usedCount; //调用次数

	private List<ApiParam> apiParams; //API参数列表
}
