# 构建与发布

普通构建：

~~~sh
python build.py
~~~

公开发行包需要维护者自己的 Android 签名密钥。在本机密钥库或 CI Secrets 中提供下面四个环境变量：

~~~text
REAR_RELEASE_KEYSTORE=<本机密钥库绝对路径>
REAR_RELEASE_KEY_ALIAS=<签名别名>
REAR_RELEASE_STORE_PASSWORD=<密钥库密码>
REAR_RELEASE_KEY_PASSWORD=<密钥密码>
~~~

~~~sh
python build.py --release
python scripts/check_public.py --apk build/rear-daily-release.apk
~~~

输出 `build/rear-daily-release.apk`。密码通过环境变量交给 apksigner，不放进命令行参数、源码或构建日志。不要将签名密钥或密码写入仓库。妥善离线备份密钥，后续更新必须沿用同一签名。

`--release` 不能与 `--with-tests` 同时使用；缺少签名配置会立即终止。普通 APK 关闭 debuggable，测试 Activity 与 instrumentation 只在显式测试构建中添加。

发布前运行 Python 脚本测试、编译 Android 测试 APK，在测试设备执行 README 中的 instrumentation 命令，再构建发布包。核对版本号、签名和 SHA-256，检查 Git 暂存文件及扫描结果，最后将 APK 和 SHA256SUMS 上传到 GitHub Release。

脚本扫描用于补充人工检查，无法识别所有格式的秘密。首次公开以明确文件清单导出项目，不迁移开发目录、个人素材、日志或旧 Git 历史。
