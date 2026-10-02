# Zygisk GPU Driver Loader 使用指南

本模块为指定应用的 Vulkan 请求加载自定义 Adreno 驱动，不修改系统或 vendor 驱动文件。第一版使用本地 ZIP；模块不附带 GPU 驱动，也没有在线下载功能。

## 环境要求

- 使用 Adreno GPU 的 Android 设备，目标应用必须是 64 位 ARM 进程。
- Magisk 或 KernelSU，以及可用的 Zygisk 实现。KernelSU 本身不提供 Zygisk，需要另行配置。
- 目标应用使用系统 Vulkan loader。OpenGL ES 驱动替换不在第一版范围内；自行加载私有 Vulkan 实现的应用不保证可拦截。
- 选择适合设备 GPU 和 Android 版本的驱动。能导入不代表驱动能运行，能加载也不代表渲染稳定。

原生模块虽然打包多种 ABI，自定义驱动加载仅在 `arm64-v8a` 路径启用。不能据此认为支持 32 位、x86、Mali 或其他 GPU。

## 驱动要求什么格式

使用 AdrenoTools 风格的原始 ZIP，不需要为了本模块重新打包。解压后应包含根目录 `meta.json`、其中 `libraryName` 指定的主库，以及驱动需要的根目录 `.so` 依赖库：

```text
meta.json
vulkan.adreno.so
dependency.so        # 可选，文件名由驱动包决定
```

最小元数据示例：

```json
{"name":"自定义驱动","libraryName":"vulkan.adreno.so"}
```

`abi` 字段可以省略。导入器实际检查主库和每个依赖库的 ELF 头，要求 ARM64、小端、64 位共享库；声明 `abi` 不能绕过检查。主库与依赖库都必须位于 ZIP 根目录，符号链接、路径穿越、重名库和嵌套库会被拒绝。驱动库最多 128 个；ZIP 和发布文件总量各不超过 512 MiB，元数据不超过 64 KiB。

导入后保留元数据和驱动库原始字节，存放在 root 管理的模块数据目录。应用启动时再校验并暂存到自己的私有目录，不从下载目录直接执行库，也不改动系统 Vulkan loader 文件。

同一个 ZIP 重复导入会复用原记录，并核对元数据、主库和全部依赖库的哈希。已存文件缺失或内容不符时导入失败，不会静默覆盖或修复旧记录。

## 安装与配置

1. 在 KernelSU 或 Magisk 管理器安装模块 ZIP，然后重启设备。覆盖安装同样需要重启才能切换已注入的原生代码。
2. KernelSU 用户从模块页面打开 WebUI；Magisk 用户使用配套配置 APK `io.github.nku100.gpudriver`，并授予所需 root 权限。
3. 在“设置 → GPU 驱动”中选择“导入驱动 ZIP”，选择本地原始驱动包。确认驱动出现在列表中。
4. 在“应用”页面打开目标应用，启用该应用的模块作用开关，在“此应用使用的驱动”中选择刚导入的驱动，并确保模块总开关开启。
5. 重启目标应用。选择变化不会替换已经运行的进程中的驱动；该选择也适用于该包的其他进程，例如 `:remote`。
6. 检查模块日志和应用实际渲染结果。发生崩溃、黑屏或图形异常时切回系统驱动，不要只凭列表中的名称判断成功。

驱动详情提供删除操作。已分配给应用的驱动不能删除，应先在所有绑定应用中切换为系统驱动。

## 日志与回退

模块数据位于 `/data/adb/zygisk_gpu_driver_loader/`，配置是 `config.json`，驱动索引是 `drivers/index.json`，持久日志是 `module.log`。优先通过配置 UI 操作，不要手工修改索引或驱动 ID。

- `HookInstalled`：已安装加载拦截，不代表驱动已经被请求或加载。
- `Loaded`：返回的入口对应已校验的私有驱动文件，不代表图形管线或窗口渲染通过。
- `SystemFallback`：本次加载使用系统驱动。
- `InvalidDriver`：驱动或私有暂存校验失败，未按选择使用自定义驱动。

正常回退方法是在应用配置中选择“系统驱动”，然后重启应用。关闭该应用的作用开关或模块总开关后，也要重启应用。配置 UI 无法打开时，可在 root 管理器禁用模块并重启设备；不要删除系统或 vendor 库。

自动回退处理的是驱动准备或加载失败。驱动已加载后发生的 GPU 崩溃、挂起或渲染错误，不保证能在同一进程里自动恢复为系统驱动。

## 当前验证范围

在 Redmi 23117RK66C、Android API 36、Adreno 750、KernelSU 32601 / Zygisk Next 1.5.0 上，已验证正式模块加载用户已有的 Qualcomm 762.46 和原始 Turnip ZIP，检查了私有库映射，并通过 Vulkan 实例、设备、队列提交和 4096 字节缓冲区回读。原始多依赖 Qualcomm 757 包只验证了导入和整组暂存，没有执行其 GPU 请求。

上述是加载和基础 GPU 工作验证，不是全部产品验收。WebUI 文件选择、应用绑定的真机完整流程，以及真实应用的图形管线、swapchain 和窗口渲染仍待验证。Magisk 配置 APK 的完整真机流程也不能由 KernelSU 验证代替。

## 从源码构建

```bash
git clone --recursive https://github.com/NKU100/zygisk-gpu-driver-loader.git
cd zygisk-gpu-driver-loader
./gradlew :module:zipRelease
./gradlew :webui-app:assembleDebug
```

模块 ZIP 输出到 `module/release/`。代码依赖固定版本的 AdrenoTools 子模块，第三方驱动不随源码或模块发布。

```bash
sh scripts/test-native.sh
./gradlew :webui:testAndroidHostTest
./gradlew :webui:buildWebUI
```

原始 ZIP 的主机导入集成测试需要主动提供本地文件；未提供时该测试会跳过，不表示原始 ZIP 已验证：

```bash
GPU_DRIVER_TEST_ZIPS=/absolute/driver-one.zip:/absolute/driver-two.zip \
  ./gradlew :webui:testAndroidHostTest --rerun-tasks
```

该测试执行生产导入代码和真实主机 shell，但不操作手机文件选择器，也不执行第三方 GPU 库。加载设计详见[第一版设计](superpowers/specs/2026-09-24-gpu-driver-loading-v1-design.md)。
