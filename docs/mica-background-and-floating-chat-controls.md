# 云母背景与聊天悬浮操作调整

日期：2026-10-01。开发工作区修改，不代表已发布或已覆盖安装用户 QA。

## 行为

- 页面选择云母时，不再把自定义全局/助手图片替换为纯色取色底层。原有背景优先级、图片路径、开关和透明度继续保留。
- 云母卡片和列表使用原有透明度设置。云母仍不启用实时模糊；无有效背景时保留完整的主题取色底层。
- 独立云母抽屉、菜单和底部面板复用已有独立背景绘制与前景对比度逻辑。内联卡片共享页面背景，不为每张卡片重复加载壁纸。
- 高级外观设置 → 顶栏，新增“顶栏背景遮盖”，默认关闭。关闭时普通顶栏的常态/滚动底色与顶栏模糊都不绘制；已保存的模糊半径和透明度不删除。
- 聊天页不再使用占位式 `Scaffold.topBar`。左侧侧栏按钮、右侧新建/菜单按钮悬浮于正文之上；按钮仍有各自的小面积材质底色，中间区域默认不绘制、不拦截触摸。
- 开启遮盖后，聊天页使用向下渐隐的材质遮盖，不恢复整条顶栏。云母使用云母色调，玻璃类才使用相应模糊能力。
- 首条正文通过列表的内容留白避开顶部按钮，滚动后可以进入按钮之间及其后方。按钮按安全区域避开状态栏/刘海，并保留 48 dp 点击区域。
- 对话标题、助手/模型信息移至菜单，重命名与消息目录/搜索仍可使用。分支来源和启用模式提示保留。宽屏继续使用常驻侧栏。

普通非聊天页面保留各自的导航布局，本次移除的是其顶栏背景遮盖；不是把全部页面改成聊天式悬浮布局。

## 实现要点

1. `GlobalAppBackground`、`AssistantBackground` 的云母底层只在无有效背景时使用。
2. `MicaPageTheme` 将页面背景与卡片材质分开，保留背景采样；卡片文字按叠加后的背景选取对比色。
3. `MicaMaterialSurface` 复用 `IsolatedAppearanceSurface` 的背景与可读性处理，不再直接画一个不透明纯色面板。
4. `BackgroundSurfaceStyle.opaqueMaterial` 仅对实色成立；云母继续保持 `usesBlur=false`。
5. `DisplaySetting.showTopBarBackground` 默认 `false`，沿用现有序列化/保存机制。页面底色与顶栏开关解耦。
6. `ChatFloatingHeader` 负责按钮和可选渐隐层；`ChatPage` 测量其实际高度，`ChatList` 使用内容留白而非裁掉顶部视口。
7. 抽屉展开回调由稳定的 `ChatPage` 协程作用域持有。侧栏展开时聊天内容会暂时移除，不能让展开动画依赖已被移除的按钮/内容作用域。

## 验证与复现

定向 JVM 测试 68 项通过。vivo V2301A / Android API 36 上的像素与控件测试 3 项通过；真实聊天导航另由 adb 界面树驱动检查通过。应用验证包版本为 `2.4.8-revised.9-20261001-173007-debug`。

- 定向 JVM 回归覆盖云母、背景选择、材质/透明度、输入区、顶栏开关与设置序列化。
- 真机测试 `MicaBackgroundRegressionTest` 使用红/蓝合成图片，不读取用户图片或修改用户 QA 设置。
- 像素检查覆盖浅色/深色全局和助手背景、云母卡片、独立面板、真实底部浮层，以及没有图片时的底层回退。
- 悬浮按钮检查包括点击事件、关闭遮盖时中间区域像素不变、开启后的渐隐层；`scripts/verify_floating_chat_controls.py` 另行启动真实聊天页，覆盖抽屉打开/返回、消息目录切换和新建入口，所有点击都来自当前界面树坐标。
- 设备测试使用独立 application ID：`me.rerere.rikkahub.revised.appearanceqa`。
- 使用项目已有的直接启动测试宿主方式，避开部分 OEM 上 `ActivityScenario` 在启动前等待的问题。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --init-script scripts/appearance-regression.init.gradle
```

安装时须先核对 `output-metadata.json` 的独立包名，然后只运行目标测试：

```text
adb -s SERIAL shell am instrument -w -r -e class me.rerere.rikkahub.MicaBackgroundRegressionTest me.rerere.rikkahub.revised.appearanceqa.test/androidx.test.runner.AndroidJUnitRunner
```

仪器测试结束后再运行真实页面检查，两者不要同时操作设备：

```powershell
python scripts/verify_floating_chat_controls.py --serial SERIAL --adb PATH_TO_ADB
```

真实页面检查与合成图夹具分开：夹具会提前初始化 Coil 图片加载器，而主 Activity 负责应用自身的加载器配置；将两者串在同一个仪器测试进程中会产生初始化顺序冲突，不能用该失败推断正常冷启动的页面行为。

`app/build/outputs/mica-background-regression/` 保存本机验证日志、合成图片和截图；属于构建产物，不进入发布包。初次被主动中止的旧宿主日志和混合测试进程日志保留为诊断记录，以 `instrumentation-pixels-final.txt` 和 `navigation-final.txt` 为验收依据。

验证范围不等同于全部页面、全部设备或所有第三方弹窗的视觉验收。此次没有对 Three.js 或其他渲染库进行修改。
