# SuperBizAgent 系统架构文档

## 一、项目概述

SuperBizAgent 是一个基于 Spring Boot 3 + AI Agent 的企业级智能业务代理系统，集成了 RAG（检索增强生成）智能问答和 AIOps 智能运维两大核心功能模块。

### 1.1 技术架构

```
┌─────────────────────────────────────────────────────────────┐
│                      前端层 (Web UI)                         │
│                    index.html + app.js                       │
└─────────────────────────────────────────────────────────────┘
                            ↓ HTTP/REST API
┌─────────────────────────────────────────────────────────────┐
│                      控制层 (Controller)                     │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐   │
│  │ ChatController│  │FileUploadCont│  │MilvusCheckCont  │   │
│  │  - /api/chat │  │  - /api/upload│  │  - /milvus/health│  │
│  │  - /api/chat_stream│            │  │                 │   │
│  │  - /api/ai_ops│  │              │  │                 │   │
│  └──────────────┘  └──────────────┘  └─────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                            ↓ 服务调用
┌─────────────────────────────────────────────────────────────┐
│                      服务层 (Service)                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│  │ChatService│  │RagService│  │AiOpsService│  │VectorIndex │  │
│  │          │  │          │  │          │  │Service     │  │
│  └──────────┘  └──────────┘  └──────────┘  └────────────┘  │
│  ┌──────────────┐  ┌──────────────┐                        │
│  │VectorSearch  │  │VectorEmbedding│                        │
│  │Service       │  │Service       │                        │
│  └──────────────┘  └──────────────┘                        │
│  ┌──────────────┐                                          │
│  │DocumentChunk │                                          │
│  │Service       │                                          │
│  └──────────────┘                                          │
└─────────────────────────────────────────────────────────────┘
                            ↓ 工具调用
┌─────────────────────────────────────────────────────────────┐
│                   Agent 工具层 (Tool)                        │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐   │
│  │DateTimeTools │  │InternalDocs  │  │QueryMetrics     │   │
│  │              │  │Tools         │  │Tools            │   │
│  └──────────────┘  └──────────────┘  └─────────────────┘   │
│  ┌──────────────┐                                          │
│  │QueryLogsTools│                                          │
│  │              │                                          │
│  └──────────────┘                                          │
└─────────────────────────────────────────────────────────────┘
                            ↓ 数据访问
┌─────────────────────────────────────────────────────────────┐
│                   基础设施层 (Infrastructure)                │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐   │
│  │MilvusClient  │  │DashScope API │  │Prometheus API   │   │
│  │Factory       │  │(阿里云 AI)    │  │(监控告警)        │   │
│  └──────────────┘  └──────────────┘  └─────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                            ↓ 数据存储
┌─────────────────────────────────────────────────────────────┐
│                      数据存储层                              │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐   │
│  │Milvus        │  │File System   │  │AIOps Docs       │   │
│  │向量数据库     │  │(上传文件)     │  │(运维文档库)      │   │
│  └──────────────┘  └──────────────┘  └─────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、项目目录结构

```
SuperBizAgent/
├── src/main/java/org/example/
│   ├── Main.java                          # Spring Boot 启动类
│   │
│   ├── controller/                        # 控制器层（REST API）
│   │   ├── ChatController.java            # 统一对话接口控制器 ⭐
│   │   │   ├── POST /api/chat             # 普通对话接口
│   │   │   ├── POST /api/chat_stream      # 流式对话接口（SSE）
│   │   │   ├── POST /api/ai_ops           # AIOps 智能运维接口
│   │   │   └── POST /api/chat/clear       # 清空会话历史
│   │   │
│   │   ├── FileUploadController.java      # 文件上传控制器
│   │   │   └── POST /api/upload           # 上传文件并自动向量化
│   │   │
│   │   └── MilvusCheckController.java     # Milvus 健康检查控制器
│   │       └── GET /milvus/health         # 向量数据库健康检查
│   │
│   ├── service/                           # 服务层（业务逻辑）
│   │   ├── ChatService.java               # 对话服务 ⭐
│   │   │   ├── 创建统一 Responses API ChatModel
│   │   │   ├── 构建系统提示词（含历史消息）
│   │   │   ├── 创建 ReactAgent
│   │   │   └── 执行对话和工具调用
│   │   │
│   │   ├── RagService.java                # RAG 服务 ⭐
│   │   │   ├── 向量检索相关文档
│   │   │   ├── 构建上下文和提示词
│   │   │   └── 流式调用大语言模型生成答案
│   │   │
│   │   ├── AiOpsService.java              # AIOps 服务 ⭐
│   │   │   ├── 构建 Planner Agent
│   │   │   ├── 构建 Executor Agent
│   │   │   ├── 构建 Supervisor Agent
│   │   │   └── 执行多 Agent 协作的告警分析流程
│   │   │
│   │   ├── VectorIndexService.java        # 向量索引服务
│   │   │   ├── 读取文件并分片
│   │   │   ├── 生成向量嵌入
│   │   │   └── 存储到 Milvus 向量库
│   │   │
│   │   ├── VectorSearchService.java       # 向量搜索服务
│   │   │   ├── 将查询文本向量化
│   │   │   ├── 在 Milvus 中搜索相似向量
│   │   │   └── 返回搜索结果（含相似度分数）
│   │   │
│   │   ├── VectorEmbeddingService.java    # 向量嵌入服务
│   │   │   └── 调用阿里云 DashScope Embedding API 生成向量
│   │   │
│   │   └── DocumentChunkService.java      # 文档分片服务
│   │       ├── 按标题分割文档
│   │       ├── 按段落分片
│   │       └── 保持语义完整性
│   │
│   ├── agent/tool/                        # Agent 工具集（工具调用）
│   │   ├── DateTimeTools.java             # 时间工具
│   │   │   └── getCurrentDateTime()       # 获取当前日期时间
│   │   │
│   │   ├── InternalDocsTools.java         # 内部文档检索工具 ⭐
│   │   │   └── queryInternalDocs(query)   # 查询内部知识库文档
│   │   │
│   │   ├── QueryMetricsTools.java         # 告警查询工具 ⭐
│   │   │   └── queryPrometheusAlerts()    # 查询 Prometheus 活动告警
│   │   │
│   │   └── QueryLogsTools.java            # 日志查询工具
│   │       └── queryLogs()                # 查询腾讯云日志服务
│   │
│   ├── config/                            # 配置类
│   │   ├── DashScopeConfig.java           # DashScope API 超时配置
│   │   ├── MilvusConfig.java              # Milvus 客户端配置
│   │   ├── MilvusProperties.java          # Milvus 配置属性
│   │   ├── DocumentChunkConfig.java       # 文档分片配置
│   │   ├── FileUploadConfig.java          # 文件上传配置
│   │   ├── WebConfig.java                 # Web MVC 配置（编码）
│   │   └── WebMvcConfig.java              # 跨域配置
│   │
│   ├── client/                            # 客户端工厂
│   │   └── MilvusClientFactory.java       # Milvus 客户端工厂 ⭐
│   │       ├── 创建 MilvusServiceClient
│   │       ├── 创建 biz 集合
│   │       └── 创建向量索引
│   │
│   ├── constant/                          # 常量定义
│   │   └── MilvusConstants.java           # Milvus 常量（集合名、维度等）
│   │
│   └── dto/                               # 数据传输对象
│       ├── AIOpsRequest.java              # AIOps 请求 DTO
│       ├── DocumentChunk.java             # 文档分片 DTO
│       └── FileUploadRes.java             # 文件上传响应 DTO
│
├── src/main/resources/
│   ├── static/                            # Web 静态资源
│   │   ├── index.html                     # 前端页面
│   │   ├── app.js                         # 前端逻辑
│   │   └── styles.css                     # 样式文件
│   │
│   └── application.yml                    # 应用配置文件 ⭐
│
├── aiops-docs/                            # AIOps 运维文档库
│   ├── cpu_high_usage.md                  # CPU 高使用率处理指南
│   ├── memory_high_usage.md               # 内存高使用率处理指南
│   ├── disk_high_usage.md                 # 磁盘高使用率处理指南
│   ├── service_unavailable.md             # 服务不可用处理指南
│   └── slow_response.md                   # 响应缓慢处理指南
│
├── uploads/                               # 上传文件存储目录
│
├── volumes/                               # Docker 数据卷（Milvus 数据）
│   ├── etcd/                              # etcd 数据
│   ├── milvus/                            # Milvus 元数据
│   └── minio/                             # MinIO 对象存储
│
├── pom.xml                                # Maven 项目配置 ⭐
├── vector-database.yml                    # Docker Compose 配置
├── Makefile                               # 构建脚本
└── README.md                              # 项目说明
```

---

## 三、核心模块说明

### 3.1 智能问答模块（RAG）

#### 模块职责
提供基于检索增强生成（RAG）的智能问答能力，支持多轮对话和流式输出。

#### 核心流程
```
用户提问
    ↓
1. 向量检索（VectorSearchService）
   - 将问题转换为向量（VectorEmbeddingService）
   - 在 Milvus 中搜索相似文档（top-k=3）
    ↓
2. 构建上下文
   - 拼接检索到的文档片段
   - 添加历史对话消息
    ↓
3. 调用大语言模型（RagService）
   - 使用统一 Responses API（由 ai.model.provider 决定供应商和模型）
   - 流式输出答案
    ↓
4. 返回结果
   - SSE 流式推送
   - 或一次性返回
```

#### 关键类说明

**ChatController** (`ChatController.java`)
- 提供 `/api/chat` 和 `/api/chat_stream` 接口
- 管理会话历史（最多 6 对消息）
- 调用 ChatService 执行对话

**RagService** (`RagService.java`)
- 核心 RAG 逻辑实现
- 向量检索 + 上下文构建 + 流式生成
- 支持带历史消息的对话

**VectorSearchService** (`VectorSearchService.java`)
- 封装 Milvus 向量搜索操作
- 支持相似度搜索（L2 距离）
- 返回带分数和元数据的结果

**InternalDocsTools** (`InternalDocsTools.java`)
- Agent 可调用的工具
- 封装向量搜索能力
- 返回 JSON 格式的搜索结果

---

### 3.2 AIOps 智能运维模块

#### 模块职责
基于 AI Agent 的自动化运维系统，采用 **Planner-Executor-Replanner** 架构，实现告警分析、日志查询、智能诊断和报告生成。

#### 核心流程
```
告警触发
    ↓
1. Supervisor Agent 编排
   - 调度 Planner 和 Executor
    ↓
2. Planner Agent 规划
   - 分析告警信息
   - 制定排查计划
    ↓
3. Executor Agent 执行
   - 调用工具查询日志
   - 调用工具查询指标
   - 检索运维文档
    ↓
4. Replanner 再规划
   - 根据执行结果调整计划
   - 循环执行直到问题解决
    ↓
5. 生成报告
   - 按照固定模板输出《告警分析报告》
   - 包含问题描述、根因分析、处理步骤
```

#### 多 Agent 架构

```
┌─────────────────────────────────────┐
│      Supervisor Agent (调度器)       │
│  - 名称：ai_ops_supervisor          │
│  - 职责：协调 Planner 和 Executor    │
│  - 提示词：SRE 自动化告警排查任务     │
└──────────────┬──────────────────────┘
               │
       ┌───────┴───────┐
       ↓               ↓
┌─────────────┐  ┌─────────────┐
│Planner Agent│  │Executor Agent│
│- 制定计划   │  │- 执行操作     │
│- 分析告警   │  │- 查询日志     │
│- 生成报告   │  │- 查询指标     │
└─────────────┘  │- 检索文档     │
                 └─────────────┘
```

#### 关键类说明

**AiOpsService** (`AiOpsService.java`)
- 构建 Planner、Executor、Supervisor 三个 Agent
- 执行多 Agent 协作流程
- 提取最终报告文本

**QueryMetricsTools** (`QueryMetricsTools.java`)
- 查询 Prometheus 活动告警
- 支持 Mock 模式（测试用）
- 返回简化后的告警列表

**QueryLogsTools** (`QueryLogsTools.java`)
- 查询腾讯云日志服务（CLS）
- 支持 Mock 模式
- 返回与告警关联的日志数据

---

### 3.3 向量数据库模块

#### 模块职责
管理 Milvus 向量数据库的连接、集合创建、索引构建和向量搜索。

#### 核心流程

**索引创建流程**：
```
文件上传
    ↓
1. DocumentChunkService 分片
   - 按标题分割
   - 按段落切分（800 字符/片）
   - 保持语义完整性
    ↓
2. VectorEmbeddingService 生成向量
   - 调用阿里云 text-embedding-v4
   - 生成 1024 维向量
    ↓
3. VectorIndexService 存储
   - 插入 Milvus biz 集合
   - 字段：id, content, vector, metadata
    ↓
4. 创建索引
   - 索引类型：HNSW
   - 度量类型：L2（欧氏距离）
```

**向量搜索流程**：
```
用户查询
    ↓
1. VectorEmbeddingService
   - 将查询文本转换为向量
    ↓
2. VectorSearchService
   - 构建 SearchParam
   - 设置 top-k=3
   - 使用 HNSW 索引搜索
    ↓
3. 返回结果
   - 文档内容
   - 相似度分数
   - 元数据信息
```

#### 关键类说明

**MilvusClientFactory** (`MilvusClientFactory.java`)
- 创建 MilvusServiceClient
- 自动创建 biz 集合（如果不存在）
- 创建 HNSW 索引

**VectorIndexService** (`VectorIndexService.java`)
- 索引单个文件或整个目录
- 调用分片服务和嵌入服务
- 批量插入向量数据

**VectorSearchService** (`VectorSearchService.java`)
- 执行向量相似度搜索
- 支持返回多个字段
- 封装搜索结果解析

---

### 3.4 文件上传模块

#### 模块职责
提供文件上传功能，并自动触发向量化流程。

#### 核心流程
```
上传文件（.txt/.md）
    ↓
1. FileUploadController 接收
   - 验证文件扩展名
   - 保存到 uploads/目录
   - 支持同名文件覆盖
    ↓
2. 自动调用 VectorIndexService
   - 读取文件内容
   - 分片 + 向量化
   - 存储到 Milvus
    ↓
3. 返回结果
   - 文件保存路径
   - 索引创建状态
```

#### 关键类说明

**FileUploadController** (`FileUploadController.java`)
- 处理 `/api/upload` 请求
- 文件验证和保存
- 自动触发索引创建

**FileUploadConfig** (`FileUploadConfig.java`)
- 配置上传路径（./uploads）
- 配置允许的扩展名（txt, md）

**DocumentChunkService** (`DocumentChunkService.java`)
- 智能分片算法
- 按 Markdown 标题分割
- 按字符数限制分片大小

---

## 四、数据模型设计

### 4.1 Milvus 向量数据模型

#### Collection: `biz`

用于存储文档分片的向量表示，支持语义搜索。

| 字段名 | 类型 | 说明 | 约束 |
|--------|------|------|------|
| `id` | VARCHAR(256) | 文档分片唯一标识 | 主键，格式：`文件名_分片索引` |
| `content` | VARCHAR(8192) | 分片文本内容 | 最大 8192 字符 |
| `vector` | FLOAT_VECTOR | 向量嵌入 | 1024 维，使用 text-embedding-v4 生成 |
| `metadata` | VARCHAR | 元数据（JSON 格式） | 包含文件名、分片索引、创建时间等 |

#### Collection 配置

```yaml
集合名称：biz
分片数：2
索引类型：HNSW
度量类型：L2（欧氏距离）
索引参数：
  - M: 16（邻居数）
  - efConstruction: 200（构建效率）
搜索参数：
  - ef: 64（搜索效率）
  - nprobe: 10（探测数）
```

#### 示例数据

```json
{
  "id": "cpu_high_usage_md_0",
  "content": "## CPU 使用率过高\n\n### 问题描述\n当服务器 CPU 使用率持续超过 80% 时...",
  "vector": [0.0123, -0.456, 0.789, ...],  // 1024 维
  "metadata": "{\"filename\":\"cpu_high_usage.md\",\"chunkIndex\":0,\"createdAt\":\"2026-01-02T10:00:00\"}"
}
```

---

### 4.2 请求/响应数据模型

#### ChatRequest（对话请求）

```java
{
  "Id": "session-123",      // 会话 ID
  "Question": "什么是向量数据库？"  // 用户问题
}
```

#### ChatResponse（对话响应）

```java
{
  "answer": "向量数据库是一种...",  // AI 回答
  "searchResults": [                 // 检索结果（可选）
    {
      "id": "doc_1",
      "content": "...",
      "score": 0.95,
      "metadata": "..."
    }
  ]
}
```

#### AIOpsRequest（AIOps 请求）

```java
{
  "userRequest": "处理 CPU 告警"  // 用户请求描述
}
```

#### FileUploadRes（文件上传响应）

```java
{
  "filename": "document.txt",    // 文件名
  "path": "./uploads/document.txt",  // 保存路径
  "message": "上传成功"           // 响应消息
}
```

---

### 4.3 内部数据模型

#### DocumentChunk（文档分片）

```java
{
  "content": "分片文本内容",      // 分片内容
  "startIndex": 0,               // 在原文档中的起始位置
  "endIndex": 800,               // 在原文档中的结束位置
  "chunkIndex": 0,               // 分片序号（从 0 开始）
  "title": "## CPU 使用率过高"   // 分片标题或上下文
}
```

#### SearchResult（搜索结果）

```java
{
  "id": "cpu_high_usage_md_0",   // 文档 ID
  "content": "分片内容",          // 分片文本
  "score": 0.95,                 // 相似度分数（越低越相似，L2 距离）
  "metadata": "元数据 JSON"       // 元数据信息
}
```

#### SessionInfo（会话信息）

```java
{
  "sessionId": "session-123",    // 会话 ID
  "history": [                   // 历史消息
    {"role": "user", "content": "问题 1"},
    {"role": "assistant", "content": "回答 1"},
    {"role": "user", "content": "问题 2"},
    {"role": "assistant", "content": "回答 2"}
  ],
  "lastActivity": "2026-01-02T10:00:00"  // 最后活动时间
}
```

---

### 4.4 配置数据模型

#### Milvus 配置

```yaml
milvus:
  host: localhost              # Milvus 主机
  port: 19530                  # Milvus 端口
  username: ""                 # 用户名（可选）
  password: ""                 # 密码（可选）
  database: "default"          # 数据库名称
  timeout: 10000               # 超时时间（毫秒）
```

#### SQLite 业务数据库

聊天记录和工具调用审计使用本地 SQLite 文件。应用启动时只创建数据库目录并打开连接，
不会自动执行建表或升级脚本。

```yaml
spring:
  datasource:
    url: jdbc:sqlite:./db/super-biz-agent.db
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1
      connection-timeout: 10000
```

SQLite 文件固定为 `./db/super-biz-agent.db`，不使用外部数据库或自动迁移流程。
初始化脚本为 `src/main/resources/db/V1_init.sql`，由发布人员手动执行；后续变更按
`V2_xxx.sql`、`V3_xxx.sql` 顺序维护并手动执行。

#### 统一 Responses API 配置

```yaml
ai:
  model:
    provider: ${AI_MODEL_PROVIDER:deepseek}
    api-key: ${AI_MODEL_API_KEY:}
    base-url: ${AI_MODEL_BASE_URL:}
    model: ${AI_MODEL_NAME:deepseek-flash}
    timeout: ${AI_MODEL_TIMEOUT:180s}
```

#### RAG 配置

```yaml
rag:
  top-k: 3                   # 检索返回的最相似文档数

dashscope:
  api:
    key: ${DASHSCOPE_API_KEY:}  # 仅用于 Embedding
  embedding:
    model: "text-embedding-v4"  # 嵌入模型
```

#### 文档分片配置

```yaml
document:
  chunk:
    max-size: 800            # 每个分片最大字符数
    overlap: 100             # 分片间重叠字符数
```

---

## 五、技术栈详解

### 5.1 核心技术

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring AI | 1.1.0 | AI Agent 框架 |
| Spring AI Alibaba | 1.1.0.0-RC2 | 阿里云 AI 扩展 |
| DashScope | 2.17.0 | 阿里云 AI 服务 SDK |
| Milvus SDK | 2.6.10 | 向量数据库客户端 |
| Jackson | 2.17.0 | JSON 序列化 |
| OkHttp | - | HTTP 客户端 |
| Lombok | - | 代码简化 |

### 5.2 AI 服务

| 服务 | 模型 | 用途 |
|------|------|------|
| Responses API | deepseek-flash / qwen3.8-flash | 智能对话与 AI Ops |
| DashScope Embedding | text-embedding-v4 | 文本向量化（1024 维） |

### 5.3 外部集成

| 系统 | 协议 | 用途 |
|------|------|------|
| Milvus | gRPC | 向量存储和搜索 |
| Prometheus | HTTP API | 告警查询 |
| 腾讯云 CLS | MCP 协议 | 日志查询 |
| DashScope | HTTP API | AI 模型调用 |

---

## 六、部署架构

### 6.1 组件依赖

```
┌──────────────────────────────────────────────────────┐
│                   SuperBizAgent                       │
│                   (Spring Boot 应用)                   │
│                   端口：9900                          │
└────────────┬─────────────────────────────────────────┘
             │
    ┌────────┼────────┬───────────────┐
    ↓        ↓        ↓               ↓
┌────────┐ ┌──────┐ ┌──────────┐ ┌──────────┐
│Milvus  │ │MinIO │ │  etcd    │ │DashScope │
│19530   │ │9000  │ │  2379    │ │  (云端)   │
│向量库  │ │对象存储│ │ 元数据存储 │ │ AI 服务   │
└────────┘ └──────┘ └──────────┘ └──────────┘
```

### 6.2 Docker Compose 部署

使用 `vector-database.yml` 部署 Milvus 栈：

```yaml
services:
  etcd:        # 元数据存储
  minio:       # 对象存储
  milvus-standalone:  # Milvus 服务
```

### 6.3 启动流程

```bash
# 1. 启动向量数据库
docker compose up -d -f vector-database.yml

# 2. 设置环境变量
export DASHSCOPE_API_KEY=your-api-key

# 3. 构建并启动应用
mvn clean install
mvn spring-boot:run

# 或使用 Makefile
make init  # 一键启动并初始化
```

---

## 七、性能优化

### 7.1 向量搜索优化

- **HNSW 索引**：使用 HNSW（Hierarchical Navigable Small World）索引加速搜索
- **批量插入**：批量插入向量数据，减少网络往返
- **缓存机制**：缓存常用查询的向量结果

### 7.2 对话优化

- **历史消息窗口**：维护最近 6 对消息，平衡上下文和性能
- **流式输出**：使用 SSE（Server-Sent Events）实现流式响应
- **超时控制**：设置 180 秒超时，防止长时间等待

### 7.3 文件处理优化

- **智能分片**：按语义边界分片，保持上下文完整性
- **重叠分片**：100 字符重叠，避免信息丢失
- **增量索引**：支持单文件索引，避免全量重建

---

## 八、安全与监控

### 8.1 安全配置

- **API Key 管理**：通过环境变量传递，避免硬编码
- **文件上传限制**：限制文件类型（.txt, .md）和大小
- **跨域配置**：配置 CORS 允许特定来源访问

### 8.2 日志记录

- **操作日志**：记录文件上传、索引创建等操作
- **错误日志**：详细记录异常堆栈
- **调试日志**：支持 DEBUG 级别调试

### 8.3 健康检查

- **Milvus 健康检查**：`GET /milvus/health`
- **应用健康检查**：可集成 Spring Actuator

---

## 九、扩展性设计

### 9.1 工具扩展

通过 Spring AI 的 `ToolCallbackProvider` 机制，可以轻松添加新工具：

```java
@Component
public class CustomTool {
    @Tool(description = "工具描述")
    public String customMethod(@ToolParam(description = "参数描述") String param) {
        // 实现逻辑
    }
}
```

### 9.2 模型切换

通过统一配置切换 AI 模型：

```yaml
ai:
  model:
    provider: deepseek
    model: deepseek-flash
    base-url: https://api.deepseek.com
```

### 9.3 向量库扩展

支持切换其他向量数据库（如 Pinecone、Weaviate），只需实现统一的向量服务接口。

---

## 十、总结

SuperBizAgent 是一个基于 Spring Boot 3 + AI Agent 的企业级智能业务代理系统，具有以下特点：

1. **双核心模块**：RAG 智能问答 + AIOps 智能运维
2. **多 Agent 协作**：Planner-Executor-Replanner 架构
3. **向量检索**：基于 Milvus 的高效语义搜索
4. **工具集成**：文档检索、告警查询、日志分析、时间工具
5. **会话管理**：上下文维护、历史管理、自动清理
6. **Web 界面**：提供测试界面和 RESTful API

系统采用分层架构，职责清晰，易于扩展和维护，适用于企业级智能客服、运维自动化等场景。

---

**版本**: v1.0.0  
**作者**: chief  
**更新日期**: 2026-01-02  
**许可证**: MIT
