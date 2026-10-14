# 贡献指南

欢迎修复问题、补充兼容性记录和简化使用流程。较大的功能改动请先创建 Issue 说明使用场景。

1. 按 README 配置 Python、JDK 和 Android SDK。
2. 保持 Java 原生界面与现有简洁风格，避免引入无必要依赖。
3. 涉及初始化脚本时运行 `python tests/test_setup_install.py`；涉及 Android 行为时运行对应 instrumentation 测试。
4. 提交前运行 `python scripts/check_public.py`，检查修改内容，不提交真实密钥、配置导出、设备日志或签名文件。

兼容性反馈请提供机型、Android / HyperOS 版本、主题管理器版本、Shizuku 版本、复现步骤和已经脱敏的错误信息。请区分「模板安装成功」与「背屏实际显示成功」。

提交代码即表示你有权按项目 MIT 许可证贡献这些改动；第三方素材和依赖需要保留来源及许可证。
