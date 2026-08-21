# 文档上传与 Milvus 索引方案

## 1. 目标

支持用户上传 Markdown 文档，并将文档切分、向量化后写入 Milvus；同时在应用启动时识别新增、修改、删除及上次索引失败的文档，保证本地文件、MySQL 索引状态和 Milvus 向量数据一致。

本文档只描述知识文档索引，不包含聊天短期记忆。

## 2. 原则

- 仅接受 `.md` 文件，后缀大小写不敏感。
- 文件同名时仍执行索引流程。上传内容可能已发生变化，不能仅以文件名判断是否跳过。
- MySQL 保存文档索引状态和内容 Hash；Milvus 保存文档分片及向量。
- 索引过程出现异常时，必须记录失败状态，供启动对账或人工重试恢复。
- `SUCCESS` 不只表示任务执行结束，还应表示 Milvus 中已存在预期数量、预期版本的分片。

## 3. 数据职责

```text
原始 Markdown 文件
  └─ aiops-docs/、uploads/

MySQL
  └─ 文档路径、内容 Hash、索引状态、分片数量、错误信息

Milvus
  └─ 分片正文、向量、文件来源、文件 Hash、分片序号等元数据
```

## 4. MySQL 表建议

新增 `knowledge_document` 表，作为索引清单（Manifest）。

```text
document_id      文档唯一标识（UUID）
source_path      文件相对路径，例如 uploads/故障处理.md
file_name        文件名
file_hash        文件内容的 SHA-256
file_size        文件大小（字节）
status           PENDING / INDEXING / SUCCESS / FAILED / DELETED
chunk_count      已成功写入 Milvus 的分片数量
indexed_at       最近成功索引时间
error_message    最近失败原因，保存脱敏、截断后的信息
created_at
updated_at
```

建议为 `source_path` 建立唯一索引。路径是一个文档的稳定业务标识；同名上传到相同位置时，更新同一条记录。

## 5. 上传接口逻辑

接口：`POST /api/upload`

```text
收到上传文件
  → 校验文件非空
  → 校验文件扩展名为 .md
  → 不通过：返回参数错误，不保存文件
  → 通过：先写入临时文件
  → 校验文件可读后，以原文件名原子替换 uploads/ 中目标文件
  → 计算 SHA-256
  → 创建或更新 knowledge_document，状态置为 INDEXING
  → 删除 Milvus 中 source_path 对应的旧分片
  → 文本分片
  → 对每个分片调用 Embedding 模型
  → 批量写入 Milvus
  → 校验写入数量和文件 Hash
  → 更新 MySQL 状态为 SUCCESS
```

文件后缀校验示例：

```java
String name = file.getOriginalFilename();
if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".md")) {
    throw new BusinessException(ErrorCode.INVALID_REQUEST, "仅支持 .md 格式文档");
}
```

上传目录不应使用客户端传来的完整路径，只使用经过文件名校验后的名称，防止路径穿越。

## 6. 同名文件更新

对于 `uploads/故障处理.md`，每次上传均执行索引，不因文件同名而跳过：

```text
新文件覆盖原文件
  → 更新 file_hash
  → 删除 Milvus 中 metadata._source = "uploads/故障处理.md" 的旧分片
  → 重建全部分片与向量
  → 写入新的 _file_hash
  → 更新 MySQL 索引状态
```

即使新旧内容 Hash 相同，初期也可按上述规则全量重建，以保证逻辑简单、行为符合“同名文件仍执行”的要求。`file_hash` 仍然需要保留，用于启动对账和排障。

## 7. Milvus 元数据

每个分片除 `id`、`content`、`vector` 外，metadata 至少包含：

```json
{
  "_source": "uploads/故障处理.md",
  "_file_name": "故障处理.md",
  "_file_hash": "SHA-256 值",
  "_extension": ".md",
  "chunkIndex": 0,
  "totalChunks": 12,
  "title": "可选的 Markdown 标题"
}
```

分片 ID 建议由 `source_path + file_hash + chunkIndex` 生成。这样同一文档内容更新后，向量 ID 也属于新版本；删除旧版本后写入新版本，不会混淆。

## 8. 启动对账

应用启动后执行一次文档对账任务，扫描配置的文档根目录：

```text
aiops-docs/
uploads/
```

对每个 `.md` 文件计算 SHA-256，并与 `knowledge_document` 和 Milvus 元数据比对。

| 条件 | 处理 |
| --- | --- |
| MySQL 无对应记录 | 新文件，执行索引 |
| 状态为 `PENDING`、`INDEXING`、`FAILED` | 上次未成功完成，重新索引 |
| 当前 Hash 与 MySQL `file_hash` 不一致 | 文件已修改，删除旧分片后重建 |
| Hash 一致、状态为 `SUCCESS`，且 Milvus 分片数量和 Hash 一致 | 已索引，跳过 |
| MySQL 有记录但本地文件不存在 | 删除 Milvus 分片，状态更新为 `DELETED` |

Milvus 校验条件：

```text
Milvus 中 _source 相同且 _file_hash 等于当前 Hash 的分片数
== knowledge_document.chunk_count
```

只有校验通过，才能认为文档已完整向量化。

## 9. 索引状态流转

```text
PENDING
  → INDEXING
  → SUCCESS

INDEXING
  → FAILED

SUCCESS
  → INDEXING        文件重新上传或内容变化

SUCCESS / FAILED
  → DELETED         本地源文件被删除
```

MySQL 与 Milvus 不存在跨数据库事务，因此以状态机保证可恢复性：先写 `INDEXING`，完成 Milvus 写入和校验后再写 `SUCCESS`。如果进程中断，记录会停留在 `INDEXING`，下次启动由对账任务重新处理。

## 10. 执行方式

初期可在上传接口中同步完成索引，用户在接口返回成功时即可检索文档。

文档变大或数量增加后，改为异步任务：

```text
上传接口保存文件并创建 PENDING 记录
  → 返回 document_id 与“索引中”状态
  → 后台任务执行向量化
  → 前端查询文档状态
```

无论同步还是异步，都复用同一套索引服务和状态机，不在 Controller 中直接编写 Milvus 逻辑。

## 11. 当前项目改造点

当前 `FileUploadController` 已在上传成功后调用 `VectorIndexService.indexSingleFile(...)`，但仅支持 `txt`、`md`，且没有 MySQL 索引状态、文件 Hash 与启动对账。

后续改造应包括：

1. 将上传格式限制为 `.md`。
2. 新增 `knowledge_document` DDL 与 Repository/Service。
3. 将 `VectorIndexService` 改为由索引服务编排状态、删旧数据、分片、向量化、校验和状态更新。
4. 将 `_file_hash` 写入 Milvus metadata，并按文档版本生成分片 ID。
5. 新增启动对账任务，扫描 `aiops-docs` 与 `uploads`。
6. 增加失败重试、手工重建索引接口和运行日志。
