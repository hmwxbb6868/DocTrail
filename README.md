# DocTrail

DocTrail 是一个知识库问答与 Agent 管理平台。用户可以围绕文档提问、查看回答来源和历史会话。管理员可以上传文档、跟踪解析状态，并查看 Agent 的执行步骤、工具调用和运行结果。

## 功能

- **知识库问答**：解析 PDF、DOCX、TXT 和 Markdown 文档，通过 Embedding 和 FAISS 检索相关片段，再由 DeepSeek 生成回答。每条回答的来源按文档归并，同一份文档只显示一个来源标签。
- **会话与权限**：区分普通用户和管理员。用户可以回看历史问答和来源，管理员负责文档管理。Java 通过请求 ID 对问答请求去重，避免同一请求重复执行。
- **Agent 运行追踪**：根据问题在知识问答和日常对话之间路由。管理端可以查看任务状态、执行步骤、工具调用和错误信息。
- **管理仪表盘**：通过 MySQL 聚合查询显示可用文档数、已结束问答数、Agent 运行数和累计成功率。

## 技术架构

浏览器通过 Nginx 访问 React 前端和 Java API。Java 负责认证、会话与文档管理，以及业务数据和运行记录的持久化。Python 负责文档解析、检索和 Agent 执行。两端通过内部 HTTP 接口协作。

| 模块 | 技术 |
| --- | --- |
| 前端 | React、Vite |
| 业务服务 | Java 21、Spring Boot、MyBatis-Plus、Flyway |
| AI 服务 | Python 3.11、FastAPI、LangChain、FAISS、DeepSeek |
| 数据与部署 | MySQL、Redis、Nginx、Docker Compose |

## 本地启动

1. 下载项目源码，安装并打开 Docker Desktop。
2. 在项目根目录把 [`.env.example`](.env.example) 复制为 `.env`，填写你自己的账号、密钥和 DeepSeek API Key，并准备 `model-cache/` 下的 Embedding 模型缓存。配置和模型准备见[本地启动指南](docs/20-本地容器化启动与验收.md)。
3. 在项目根目录执行：

   ```powershell
   docker compose up -d --build --wait --wait-timeout 660
   ```

启动后，从 [用户端](http://127.0.0.1:8080/login) 或 [管理端](http://127.0.0.1:8080/admin/login) 登录。如果修改了 `NGINX_HOST_PORT`，请使用设置的端口。首次构建需要联网下载镜像和依赖。`.env`、模型缓存和运行数据请留在本机。

## 项目文档

- [本地开发环境](docs/02-环境搭建指南.md)
- [接口概览](docs/03-接口契约.md)
- [浏览器 API 契约](contracts/public-openapi.yaml) · [Java 与 Python 的内部 API 契约](contracts/ai-openapi.yaml)
- [错误码表](docs/04-错误码表.md) · [依赖版本清单](docs/05-依赖版本清单.md)
- [本地启动与验证](docs/20-本地容器化启动与验收.md) · [已知部署限制](docs/22-本地交付剩余风险评估.md)

## 来源与许可

DocTrail 的服务架构与核心功能参考了 EvanYao826 的 AgentCraft。DocTrail 在此基础上完善了接口契约、数据隔离、引用展示、Agent 审计、仪表盘统计和本地容器化交付。项目采用 Apache License 2.0。详见 [AgentCraft](https://github.com/EvanYao826/AgentCraft)、[NOTICE](NOTICE) 和 [LICENSE](LICENSE)。
