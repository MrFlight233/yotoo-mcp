# yotoo-mcp

一个基于 Spring Boot + Spring AI MCP Server 的示例项目，用于将本地/远程 HTTP API 动态注册为 MCP 工具，供大模型通过 MCP 协议调用。

## 项目功能

- 提供 MCP Server（同步模式），支持工具能力和工具变更通知。
- 支持两类工具来源：
  - 注解工具：通过 `@Tool` 暴露固定方法（示例：`livingCity`）。
  - 动态工具：基于 `ApiDef + ApiParam` 组合生成工具定义与参数 Schema。
- 动态工具执行时，统一转发到 `ApiInvoker` 发起 HTTP 请求。
- 参数策略：参数表中的参数默认按 query 参数处理（路径占位符参数除外）。
- 提供测试接口用于刷新工具列表和验证示例 API。

## 主要模块

- `com.yotoo.mcp.config.DynamicToolCallbackProvider`  
  动态构建 MCP 工具（名称、描述、JSON Schema、执行函数）。
- `com.yotoo.mcp.cache.ApiBeanCache`  
  示例缓存（`ApiDef` 列表 + `ApiParam` 映射关系）。
- `com.yotoo.mcp.service.ApiInvoker`  
  统一 HTTP 调用入口，支持模拟调用与真实网关调用。
- `com.yotoo.mcp.controller.TestController`  
  提供 `/refresh/tools`、`/get/weather/{city}`、`/get/id`、`/calculate` 示例接口。

## 运行要求

- JDK 17（项目使用 Spring Boot 3.x）
- Maven（当前环境建议 3.6.3+；项目内已兼容旧版本 toolchains 插件）

## 快速启动

### 1) 端口与配置

默认配置文件：`src/main/resources/application.yml`

- 服务端口：`8080`
- MCP 消息端点：`/mcp/message`
- 网关地址：`api.gateway`
- 是否模拟调用：`spring.ai.mcp.simulate-invoke`

### 2) 构建与运行（推荐本项目专用 JDK17 脚本）

项目根目录已提供：

- `mvn17.cmd`
- `mvn17.ps1`

示例命令（Windows PowerShell）：

```powershell
.\mvn17.ps1 -DskipTests install
.\mvn17.ps1 spring-boot:run
```

或使用 cmd：

```bat
mvn17.cmd -DskipTests install
mvn17.cmd spring-boot:run
```

## Toolchains 说明（与其他 Java8 项目共存）

本项目 `pom.xml` 已启用 `maven-toolchains-plugin`，要求 JDK 17。  
请确保用户目录存在 `~/.m2/toolchains.xml`（可参考项目根目录 `toolchains.xml.example`）。

这样可以做到：

- 其它 Java8 项目继续使用 Java8。
- 本项目通过 toolchain 指定编译 JDK 为 17。

注意：某些 Maven 插件（如 Spring Boot repackage）会随 Maven 进程 JDK 运行，因此本项目建议使用 `mvn17` 脚本执行命令。

## MCP 客户端连接最小示例

服务启动后（默认 `http://127.0.0.1:8080`），可按 SSE 方式连接。

### 1) 服务端端点

- SSE 连接端点：`http://127.0.0.1:8080/sse`
- 消息端点：`http://127.0.0.1:8080/mcp/message`

其中消息端点来自 `application.yml` 配置：`spring.ai.mcp.server.sse-message-endpoint=/mcp/message`。

### 2) 客户端配置示例（JSON）

```json
{
  "mcpServers": {
    "yotoo-mcp-local": {
      "transport": "sse",
      "url": "http://127.0.0.1:8080/sse",
      "messageEndpoint": "http://127.0.0.1:8080/mcp/message"
    }
  }
}
```

如果你的 MCP 客户端不支持单独填写 `messageEndpoint`，仅配置 `url` 也可以，客户端会在握手阶段使用服务端返回的消息地址。

### 3) 连通性检查

- 先访问 `http://127.0.0.1:8080/sse`，应能建立 SSE 连接（浏览器会保持挂起）。
- 在 MCP 客户端里连接后，检查工具列表中是否出现 `livingCity`、`getWeather`、`calculate`、`getId`。
- 调用 `GET /refresh/tools` 后，客户端应收到工具列表变更通知。

## 示例接口

- `GET /refresh/tools`：通知 MCP 客户端刷新工具列表。
- `GET /get/weather/{city}`：天气示例接口。
- `GET /get/id`：读取请求头 Authorization 并返回示例用户 ID。
- `POST /calculate`：加减乘除示例。

请求体示例：

```json
{
  "a": 12,
  "b": 3,
  "op": "div"
}
```

