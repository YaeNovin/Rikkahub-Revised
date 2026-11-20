# 记忆与对话分支审阅

检查日期：2026-11-20，源码基于 `2.4.8-revised.11`。本文件区分源码确认的问题、需要复现的风险和后续优化；本轮实现任务是 GitHub Token 与卡片，没有顺带改写记忆或分支的数据语义。

## 已有保护

- 分支通过 `createForkConversationSnapshot` 截取所选历史，复制附件并建立独立会话；保留来源会话和消息信息，删除来源不会级联删除分支。
- 分支重新计算临时模式的剩余轮次，清空世界书运行状态，避免把分叉点之后的冷却状态带入新会话。
- 记忆有稳定 UID、修订号、内容哈希、作用域、来源和删除标记。向量回写检查内容和模型配置；全局清除使用写入 epoch 防止旧任务回填。
- 当前对话索引按选中消息建立，检索排除已在请求中的内容；没有向量模型或供应商失败时保留关键词回退。

## 应修复

| 优先级 | 结论与证据 | 建议与验收 |
| --- | --- | --- |
| P1 | **已确认遗漏**：`ChatService.createForkConversationSnapshot` 复制 `lorebookIds`，但未复制 `disabledLorebookIds`，新会话使用空默认值。来源中禁用的条目可能在分支中重新启用。 | 同步禁用列表；覆盖助手继承、对话禁用和分支后修改互不影响的测试。 |
| P1 | **待复现并发风险**：`MemoryRepository.deleteMemoriesOfAssistant` 不走全局清除所用的 epoch/互斥协议；删除标记阻止旧 UID 回归，但提取任务可能生成新的 UID。 | 检查助手级清除与正在提取/索引的竞争，建立作用域级取消/写入版本；验证清除后无延迟回填。 |
| P1 | **模型边界**：消息分支是各节点的备选项，未保存后续节点对早期选项的父子依赖；切换早期回复仍可能保留在另一回复上生成的后续消息。 | 明确“仅替换回复”和“切换完整路径”的语义，再设计父消息关联或新会话迁移；避免直接截断用户历史。 |
| P2 | **恢复边界**：`Conversation.currentMessages` 直接下标访问；`MessageNode.currentMessage` 对空消息或越界抛出异常。 | 在导入、恢复和数据库读取处统一验证节点并报告受损项；保留原始备份，禁止静默丢弃历史。 |
| P2 | **分支配置不完整**：快照未携带 `folderId`，分支默认离开原文件夹；`memoryMode` 被继承，但分支记忆模式控件与服务写入口被锁定。 | 分别明确分支分组策略与记忆可编辑策略，添加界面说明和行为测试，避免用户误以为配置已生效。 |

## 可优化和新增

- **检索成本**：`MemoryRetrievalTransformer` 通过 `getRecordsForConversation` 加载助手全部记忆及向量，在 Kotlin 中线性计算相似度；`getRecords` 每次重建当前对话草稿。建议先做 SQL 作用域/生命周期筛选、关键词候选与版本缓存，再在候选集计算向量，采集大数据量下的耗时和分配。
- **首字延迟**：查询向量生成处于聊天输入转换链，未见统一的检索时间预算。建议在网络层超时之上增加请求级预算、取消与关键词降级，并展示来源和命中原因。
- **任务状态**：`MemoryExtractionService.conversationLocks` 按会话保留互斥对象，未见移除；应使用有生命周期的协调器或带引用计数的清理，不能简单在任务结束时删除正在被等待的锁。
- **存储维护**：`memory_run`、来源记录和删除标记需要分别制定保留策略。运行记录可分页和定期归档；删除标记应先考虑同步恢复语义。当前维护计数只覆盖记忆与对话块，建议补充向量字节数、运行记录、来源和失效索引统计。
- **分支管理**：增加分叉点预览、来源已删除提示、分支搜索/比较和切换时滚动定位；长历史分叉可评估附件引用计数与写时复制，但必须保留独立删除与附件回收边界。
- **测试补足**：优先覆盖提取中切分支、编辑分叉点、清除时向量返回、来源删除后分支附件、恢复损坏节点，以及大量分支/记忆下的请求延迟。以上为后续验证清单，不能视为已通过真机测试。

## 代码入口

- [ChatService.kt](../app/src/main/java/me/rerere/rikkahub/service/ChatService.kt)：分支快照、附件复制、记忆模式更新。
- [Conversation.kt](../app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)：节点选择与来源元数据。
- [MemoryExtractionService.kt](../app/src/main/java/me/rerere/rikkahub/data/memory/MemoryExtractionService.kt)：提取计划、检查点、会话锁。
- [ConversationMemoryIndexService.kt](../app/src/main/java/me/rerere/rikkahub/data/memory/ConversationMemoryIndexService.kt)：可见历史索引和向量回写。
- [MemoryRetrievalTransformer.kt](../app/src/main/java/me/rerere/rikkahub/data/ai/transforms/MemoryRetrievalTransformer.kt)：候选加载、向量与关键词检索。
- [MemoryRepository.kt](../app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt)、[MemoryDAO.kt](../app/src/main/java/me/rerere/rikkahub/data/db/dao/MemoryDAO.kt)：修订、删除与运行记录。
