# 每日背屏 · RearDaily

[下载安装包](https://github.com/skyswords7/rear-daily/releases) · [使用指南](docs/rear-screen-setup.md) · [隐私说明](docs/privacy.md) · [反馈问题](https://github.com/skyswords7/rear-daily/issues)

**首个公开测试版 · v0.8.0-beta · MIT**

小米系统背屏的图片与视频伴侣 App。素材保存在手机中，通过只读 ContentProvider 提供给 MAML 背屏模板。

## 首次使用

首次打开 App，会看到「自动设置背屏」和「手动设置背屏」两个入口。点击后在同一弹窗内查看和切换引导；也可稍后从 **设置 → 背屏设置与使用指南** 进入。已连接背屏的老用户升级后直接进入首页。

推荐流程：在手机安装并启动 Shizuku → 回到 App 点击「授权并自动设置」→ 在系统页面中点「应用」。不需要连接电脑或复制命令；Shizuku 首次启动可通过无线调试完成。

备用流程：展开「使用电脑 ADB 设置」→ 在电脑执行 App 生成的一条命令 → 在手机系统预览中点「应用」。App 自动生成模板、独立目录和系统元数据，无需事先在设置中创建壁纸。之后换图、换视频在 App 内完成，无需持续连接电脑或保持 Shizuku 运行。

[完整操作手册](docs/rear-screen-setup.md)包含 Shizuku 启动、电脑 ADB、手动备份替换、恢复和排错。

## 日常使用

- **背屏**：预览当前内容、收藏、导入相册图片或视频；「显示选项」可切换已保存的图片和视频。「换张AI生成图片」会自动获取新选题、生成一张图片并应用，按你配置的 AI 服务计费。
- **创作**：选题提示词、生图提示词与创作偏好默认只显示四行。点「修改」查看和编辑完整内容；保存生效，取消保留原文。生图提示词的简短预览优先展示画面描述，完整编辑仍保留选题语境和构图要求。
- **收藏 / 历史**：收藏长期保留，历史保留最近 30 条；可预览并重新应用。
- **设置**：集中管理每日自动更换、AI 服务、搜索关键词和排除词。API Key 使用 Android Keystore 加密保存在本机，仅在调用你配置的 AI 服务时发送给该服务。

## 兼容范围

- Android 12 及以上；设备需具有小米系统主题管理器和背屏服务。
- 自动预览使用系统主题管理器的内部资源模型。当前实测主题管理器 11.5.3.1；其他机型、系统版本需实测，不能保证所有 Android 或所有 HyperOS 版本通用。
- Shizuku（API v13+）或 ADB 需能写入主题管理器的外部文件目录；若 ROM 拒绝写入，需使用有权限的文件工具走手动流程。
- 存储根目录按当前 Android 用户获取，模板 ID 按 App 安装生成；没有固定设备序列号或其他手机的壁纸 ID。
- 模板源文件由本项目提供，不依赖从手机导出的主题包，也不打包系统主题管理器字节码。

## 构建

依赖 Python 3、JDK（java / javac 在 PATH 中）、Android SDK Platform 35 或更高版本、Android Build Tools。设置 ANDROID_HOME 或 ANDROID_SDK_ROOT 后，在项目目录执行：

~~~sh
python build.py
~~~

输出 build/rear-daily.apk。默认使用项目本地 debug.keystore；首次构建会生成开发签名，请保存它以便后续覆盖更新。构建出的普通 APK 关闭调试且不含 instrumentation。正式发布使用独立签名，见[发布构建](docs/releasing.md)。开发签名和正式签名的 APK 无法直接相互覆盖安装。

默认自动查找 SDK 中版本最高的工具及平台。可用 REAR_BUILD_TOOLS 指定构建工具目录、REAR_ANDROID_JAR 指定 android.jar。未设置 SDK 环境变量时，兼容查找 ../tools/sdk。Windows 与 POSIX 可执行文件后缀均已处理；本次实际构建验证在 Windows 完成。

首次构建会从 Maven Central / Google Maven 下载 dependencies.json 中固定版本、SHA-256 校验的 Shizuku API 13.1.5 和 AndroidX Annotation 1.3.0；缓存位于 build/deps，后续可离线复用。需要联网下载依赖。对应许可证随源码及 APK 保存。

构建前自动执行 package-template.py，从 maml/manifest.xml 同步 App 内模板并生成独立的 maml/rearscreen，所有路径相对于项目。

## 验证

~~~sh
python tests/test_setup_install.py
python build.py --with-tests
adb install -r build/rear-daily.apk
adb shell am instrument -w -e action ui com.reardaily.app/.DailyInstrumentation
adb shell am instrument -w -e action shizuku com.reardaily.app/.DailyInstrumentation
adb shell am instrument -w -e action setup com.reardaily.app/.DailyInstrumentation
adb shell am instrument -w -e action unit com.reardaily.app/.DailyInstrumentation
adb shell am instrument -w -e action v05 com.reardaily.app/.DailyInstrumentation
~~~

脚本集成测试需 bash（Windows 可使用 Git Bash），在 build 下的临时目录运行，不修改系统壁纸。Android 初始化与界面测试使用隔离的文件和设置，不应用测试壁纸、不调用付费 AI 接口。界面测试包含首次引导、四行预览、编辑取消/保存、输入校验及 Activity 重建恢复。验证完成后重新运行 python build.py 并安装普通 APK。

[测试范围与已知限制](docs/testing.md)

## 初始化实现

- src/com/reardaily/app/RearSetup.java：设备检测、模板生成、系统预览适配、回执和背屏读取状态。
- src/com/reardaily/app/RearSetupDialog.java：Shizuku / ADB 自动流程与手动说明。
- src/com/reardaily/app/ShizukuSetup.java：连接状态、授权、跨界面安装状态与服务释放；通过官方 REQUEST_BINDER 协议补充连接恢复，校验管理器 UID 和服务类型。
- src/com/reardaily/app/RearSetupUserService.java：受限安装服务，核对调用 UID、用户目录及脚本摘要；不开放任意命令执行接口。
- assets/setup/install.sh：校验、独立安装、重复执行、备份替换和恢复。
- maml/manifest.xml：自有的图片 / 视频桥接模板。

安装回执仅表示文件写入成功。弹窗分别识别主题预览读取与真实背屏服务读取；显示历史读取时间，不把「已缓存素材」当成系统背屏已应用。

## 参与与许可

欢迎通过 Issue 提交兼容性反馈，通过 Pull Request 改进项目。请先阅读[贡献指南](CONTRIBUTING.md)，不要提交 API Key、设备日志、配置导出或签名文件。

项目代码、MAML 模板和内置欢迎素材采用 [MIT](LICENSE) 许可。第三方依赖保留各自许可证，详见 [assets/licenses](assets/licenses) 与 [参考说明](assets/references.txt)。在线下载的作品版权归原作者，不随本仓库重新分发。
