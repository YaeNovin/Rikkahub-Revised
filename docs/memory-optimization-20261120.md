# 记忆模块首轮优化

2026-11-20，基于[记忆与对话分支审阅](memory-branch-review-20261120.md)实施。范围为后台提取、索引写入和聊天检索，不改变历史分支路径或删除聊天消息。

## 实现

- **清除与在途任务**：写入版本包含全局版本和助手所有者版本。清除助手记忆与提取入库共用互斥协议；旧提取不能通过生成新 UID 回填。其他助手和全局记忆的正常任务不因单个助手清除而失效；清除全部记忆仍使所有旧版本失效。版本在删除结束时更新，因此删除执行期间启动的任务同样不能在结束后回填。
- **完整写入链**：提取任务进入会话队列前保存版本，检查点、创建、情景记忆状态变更、个人记忆向量和对话索引回写均校验相同版本。网络请求期间不持有数据库写入互斥锁。
- **会话锁回收**：锁条目计数同时包含持有者和等待者；成功、失败、取消均释放引用，仅在没有任何使用者时回收。不同会话可并行执行，访问历史会话不再不断积累互斥对象。
- **数据库筛选**：在 Room 查询中先筛选助手、对话作用域、生命周期及情景记忆开关，再读取向量。保留旧类型和生命周期别名的转换语义。预览默认仍能读取已完成或已替代的记忆，正常 RAG 只读活动记忆。
- **查询向量预算**：全部查询模型共用 1,200 ms 网络预算。超时保留已完成的向量，并继续关键词检索；用户停止生成所产生的父协程取消继续向上传播。预算只约束查询向量网络等待，不承诺所有设备上的总检索耗时上限。

没有修改 Room 表结构、版本或持久化格式，也不清除现有记忆或向量。写入版本仅存在于进程中，进程退出时旧后台任务一并结束。

## 验证

- 最终 Debug 主包及 AndroidTest APK 构建成功，59 项 JVM 定向回归全部通过，包含 8 项新增锁与查询预算用例。
- vivo V2301A / Android 16 上 12 项真机回归全部通过，使用独立 Debug 包 `me.rerere.rikkahub.revised.githubqa`，未访问常用 QA / 正式版的数据。
- 清除前挂起的提取不能生成新 UID 回填；其他助手及全局任务仍可继续；清除执行期间取得的写入版本在结束后失效，新任务仍可正常保存。
- Room 实际查询与 Kotlin 作用域规则一致，覆盖空会话、大小写类型、旧生命周期别名、事实/情景区分及已完成记忆的预览边界。
- 完整提取链路覆盖响应返回前清除、切换记忆模式后丢弃响应、仅提取不建向量、正常 RAG 建索引。
- 4 秒延迟的合成查询在 3 秒断言窗口内返回，回复上下文保留关键词命中的记忆，证明实际 Provider 调用可被共享时间预算取消。
- 锁回归覆盖等待者取消、后来者与旧等待者共用锁、不同会话并发、异常释放和连续 1,000 个会话后的条目回收。

完整设备用例耗时 2.429 秒。结果与日志位于忽略的 `build/reports/memory-20261120/`，最终构建日志位于 `build/reports/qa-build/20261120-223812/`。测试后临时亮屏设置已恢复为原值 `0`。合成数据及拦截网络用例不代表真实供应商联调或全应用压力测试。

可复现构建（只替换设备序列号，不填写真实供应商凭据）：

```sh
python scripts/build_qa.py :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.memory.*' --tests 'me.rerere.rikkahub.data.ai.transforms.Memory*' --tests me.rerere.rikkahub.data.ai.tools.MemoryToolsTest --tests me.rerere.rikkahub.ui.components.message.tools.MemoryToolResultTest --init-script scripts/github-cards-qa.init.gradle --offline
adb -s DEVICE_SERIAL install -r --user 0 app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s DEVICE_SERIAL install -r --user 0 app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument --user 0 -w -r -e class me.rerere.rikkahub.MemoryIdentityRegressionTest,me.rerere.rikkahub.MemoryIsolationRegressionTest,me.rerere.rikkahub.MemoryExtractionOnlyTest me.rerere.rikkahub.revised.githubqa.test/androidx.test.runner.AndroidJUnitRunner
```

## 后续

- 缓存当前可见消息的分块草稿，避免每次检索重建完整草稿。
- 评估大数据量候选检索及向量读取的分页、索引和耗时；本轮先下推作用域和生命周期条件，不截断可能有效的长期记忆。
- 为运行记录、来源和删除标记分别设计维护策略，并完善索引空间统计。
- 完整路径分支、受损历史恢复和世界书禁用列表属于后续独立任务，继续见原审阅清单。
