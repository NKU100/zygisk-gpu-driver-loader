# 构建与测试

## 构建

克隆时初始化固定版本的子模块：

```sh
git clone --recursive https://github.com/NKU100/zygisk-gpu-driver-loader.git
cd zygisk-gpu-driver-loader
```

使用 JDK 21，配置 Android SDK（`ANDROID_HOME` 或本地 `local.properties`）。SDK、NDK 和构建工具版本以项目构建配置为准。

```sh
# 原生模块、导入助手和生产 WebUI
./gradlew :module:zipRelease

# 独立配置 APK，用于开发和本地安装
./gradlew :webui-app:assembleDebug

# 仅构建生产 WebUI
./gradlew :webui:buildWebUI
```

模块 ZIP 输出到 `module/release/`；调试 APK 输出到 `webui-app/build/outputs/apk/debug/`。调试 APK 使用开发签名，不应将其描述为已配置正式发布签名的 APK。

当前发布工作流上传模块 ZIP、更新元数据和更新日志，不包含独立配置 APK。模块不打包第三方 GPU 驱动。

模块身份定义在 `module.gradle.kts`。模块版本由 Git 标签和提交计数生成；`v*` 标签触发正式发布工作流，普通主干构建生成 `ci` 预发布。具体触发条件见 `.github/workflows/`。

## 自动测试

```sh
sh scripts/test-native.sh
./gradlew :module:testDebugUnitTest :webui:testAndroidHostTest
./gradlew :webui:wasmJsBrowserTest
```

浏览器测试需要可用的 Chrome 或通过 `CHROME_BIN` 指定兼容浏览器。普通浏览器预览使用示例数据，不能证明 root 桥接或驱动加载有效。

模块 ZIP 的安装脚本可在主机验证：

```sh
sh scripts/test-module-install.sh /absolute/module.zip arm64 false
sh scripts/test-module-install.sh /absolute/module.zip arm64 true
sh scripts/test-module-install.sh /absolute/module.zip arm true
sh scripts/test-module-install.sh /absolute/module.zip x86 true
sh scripts/test-module-install.sh /absolute/module.zip x64 true
sh scripts/test-module-install.sh /absolute/module.zip riscv64 false
```

最后一个参数表示系统是否支持 32 位应用。这些检查验证包内安装与校验脚本，不验证对应 ABI 的进程运行或 GPU 兼容性。

原始驱动 ZIP 集成测试需要显式提供本地文件：

```sh
GPU_DRIVER_TEST_ZIPS=/absolute/driver-one.zip:/absolute/driver-two.zip \
  ./gradlew :webui:testAndroidHostTest --rerun-tasks
```

未提供输入时，该集成测试会跳过。测试会执行导入和校验，但不会执行 GPU 驱动。

## 设备验收

安装最终构建的模块并重启，在目标宿主中验证 APK 或 KernelSU WebUI。至少核对以下行为：

- 旧配置的驱动绑定、应用开关、备注和界面偏好保留。
- 合法 ZIP 可导入，异常 ZIP 被拒绝且已有数据不变。
- 目标应用加载所选驱动，结合库映射、Vulkan 查询及实际渲染判断结果。
- 切换系统驱动并重启应用后恢复系统库；未选中的应用不受影响。
- 删除有绑定的驱动会重置所有相关绑定；失败时保留并报告实际完成的步骤。
- 日志页面能显示当前进程的加载结果，应用设置不再出现未实现的日志控件。

驱动代码属于应用进程的一部分。主机测试、模拟器、成功构建和非空加载句柄均不能替代真实 Adreno 设备的运行验证。已验证范围及限制见[使用指南](gpu-driver-guide.md)。
