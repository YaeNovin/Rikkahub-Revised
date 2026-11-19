# Lint 第一批修复

日期：2026-09-15。基于 `2.4.8-revised.9` 的 Lint 清单处理第一批问题；当前为未发布修改，不调整发行版本号。

## 修改内容

| 原报告 | 原数量 | 修改 |
| --- | ---: | --- |
| MissingPermission | 1 Error | 视频完成通知复用统一通知发送器；调用点显式检查 Android 13+ 通知权限，保留撤销权限时的异常处理。无法通知不改变视频任务结果 |
| NewApi | 2 Error | 系统动态色调用增加直接的 API 31 守卫，旧系统仍走原有自定义/预设主题回退 |
| ContextCastToActivity | 1 Error | 聊天列表从 LocalActivity 获取 Activity；监听按宿主、生命周期和列表绑定，读取最新开关及滚动比例；后台和全屏图形预览期间不滚动底下的聊天 |
| RememberReturnType | 1 Error | 灵感编辑器在设置加载后通过副作用初始化，以助手和每次打开的会话为边界；准备完成前不展示旧编辑内容或允许保存默认草稿 |
| StringFormatInvalid / StringFormatCount | 2 Error + 8 Warning | 日语、韩语、俄语和繁体中文恢复清理结果的全部格式参数，保持原资源 ID 与调用契约 |
| Aligned16KB | 1 Warning | 在依赖产物转换时移除上游 Termux AAR 的 JNI 副本，只使用 workspace 的实现；取消 pickFirst，同名库重复时使构建失败；链接选项明确要求 16 KB 对齐 |

灵感编辑器的同次打开、旋转恢复会保留未保存修改；新的打开会话重新读取已保存设置。
通知发送器移除了原有 MissingPermission 抑制，使用实际检查和异常处理。

## Termux 来源

`build-logic` 的 `rikkahub.workspace-termux` 插件注册 AAR 产物转换。
仅处理与版本目录 `termux` 对应的 `terminal-emulator-<version>.aar`，其他 AAR 原样传递。
转换结果只移除 `jni/<ABI>/libtermux.so`，保留 classes.jar、清单、资源及归属文件；下载缓存中的原始 AAR 不修改。
应用继续使用上游 Java 接口，JNI 由 `workspace/src/main/cpp/termux_pty.cpp` 提供。

去掉 `pickFirst` 后，任何未按预期移除的同名 JNI 输入会在合并阶段暴露，避免随依赖顺序静默切换。
本轮还核对了实际转换：移除 4 个 ABI 的旧 JNI，其他 13 个 ZIP 条目字节不变。

## 验证

- 第一批七类 Release Lint 检查通过，报告为 `No issues found`：0 Error、0 Warning。基线中的 7 条 Error 与 9 条 Warning 已在本批定向检查中消除；并未重新声称完整 32 类检查全部通过。
- 10 项 app 定向 JVM 测试通过：主题来源/回退、编辑会话恢复与草稿保护、所有现有语言清理文案的参数和实际格式化。
- 2 项 build-logic 测试通过：仅移除指定 JNI、保留其他内容及原缓存、输出确定性。
- QA Kotlin 编译与 QA APK 构建通过，合并阶段无需 pickFirst。
- 实际 QA 通用 APK 中 28 个 ARM64/x86_64 原生库的 ELF 装载段均符合 16 KB 对齐；Termux 合并文件与 workspace 原产物哈希一致，APK 中的 Termux 与对应剥离产物一致。
- 上述结果不替代手机上的通知权限切换、旋转/重开编辑器、音量键和真实终端使用验证；本轮未进行实机安装或付费模型调用。

复现定向检查（Windows 使用 `gradlew.bat`）：

```bash
./gradlew :build-logic:test :app:testDebugUnitTest --tests '*InspirationEditorStateTest' --tests '*ThemeColorSourceTest' --tests '*FileCleanupStringContractTest' :app:assembleQa --no-configuration-cache
./gradlew :app:lintRelease --init-script scripts/lint-batch1.init.gradle --no-configuration-cache
```

```powershell
.\scripts\verify-workspace-termux.ps1 -ApkPath app/build/outputs/apk/qa/app-universal-qa.apk -Variant qa
```

`lint-batch1.init.gradle` 仅在显式传入时检查这七类，报告写入各模块 `build/reports/lint-batch1.*`。
默认完整 Lint 及其规则保持不变。本批结论不代表缺失翻译、资源读取、语言观察等后续批次已清零。
