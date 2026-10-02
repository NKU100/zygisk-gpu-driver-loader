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

KernelSU WebUI 已完成原始 Turnip ZIP 的系统文件选择、导入、列表与详情显示、为 `com.unity.vrsdemo.vulkan` 选择驱动及配置落盘。Unity 实际启动使用私有 Turnip 库，日志报告 `Loaded`、renderer 为 `Turnip Adreno (TM) 750`、版本为 `0x06463063`，窗口显示棋盘场景与几何体。

此轮真机验证发现应用既有 `files` 目录可能为 `0771`。暂存现在接受属主与属组匹配的 `0700` 或 `0771` 应用目录，不修改它的权限；模块自己的子目录仍要求 `0700`。

KernelSU WebUI 的已绑定驱动删除保护已验证：详情提示先解除应用绑定，点击删除不会进入确认页，驱动索引与绑定配置保留。通过 WebUI 切换为系统驱动并执行“重启应用”后，Unity 新进程映射 `/vendor/lib64/hw/vulkan.adreno.so`，没有映射模块的私有驱动或 hook 库，窗口继续显示棋盘场景与几何体。

未绑定的 Turnip 驱动可进入删除确认页：删除操作为上方红色文字，取消操作为下方中性色描边按钮。点击取消后驱动保留，主库哈希未变；这项验证未实际删除 Turnip。

同步模板后，KernelSU 管理器中的 WebUI 已热更新并重新打开。应用列表显示 Unity 与 DevCheck；日志页读取持久日志，显示 Unity 新进程的 `Loaded`、PID/TID 和时间，并可在原卡片展开私有驱动路径。此次进程日志未发现 JavaScript 报错，但有缺失 `favicon.ico` 的资源请求；未将这项非关键请求视为页面渲染失败。热更新前的 WebUI 资源保存在设备模块数据目录的 `webroot-before-template-869578c`，驱动与配置未替换。

配套 APK 已在同一 KernelSU 真机覆盖安装并启动，可读取模块状态、目标应用数量与已导入驱动列表。同步模板的 root 应用枚举后，列表可显示 Unity、DevCheck 等应用，首页正确识别 KernelSU。通过 APK 为 Unity 选择 Turnip 并执行重启后，配置写入对应驱动 ID，新进程记录 `Loaded`，映射私有 Turnip 库，窗口显示 `Turnip Adreno (TM) 750` 与棋盘场景。此结果不等于 Magisk 环境验收。

APK 的系统文件选择器已取得原始 Turnip ZIP。合并传输短读后再次重复导入，页面明确显示“驱动已就绪”，暂存目录清理，索引与应用配置哈希均未变化，已安装主库 SHA-256 保持 `fdd378520022f88b0363dd1f77f6989332730271712621523075fe4eb4de2a09`。此轮约 17 MB 解压库在确认选择后的 111–162 秒之间完成；这是轮询得到的时间范围，不是精确耗时。逐块启动 root 命令仍有明显开销，不能认为性能问题已解决。

APK 现已保留本次操作的“驱动已就绪”结果，重复导入且列表不变时也能显示；开始下一次导入或删除会清除旧结果。独立的非 GPU 测试包已通过系统文件选择器导入，页面显示成功结果，再通过详情与二次确认实际删除。删除后索引与对应目录均已移除，原有 Turnip、旧测试包及 Unity 绑定配置保留；测试 ZIP 仍保留，可重新导入。传输性能尚待优化。

通过 APK 选择不含驱动元数据的模块 ZIP，页面显示“驱动 ZIP 或元数据无效”，旧成功提示清除，没有生成驱动记录或遗留暂存目录。再次打开文件选择器并取消后正常返回列表，不显示导入失败。两次操作前后的驱动索引与应用配置哈希一致。这项验证覆盖错误包类型与选择取消，不代表所有损坏 ZIP 或不兼容 ELF 的真机路径均已验收。

传输进一步改为每次 root 命令最多写入三块，各块独立解码，任一写入失败停止后续命令；保留文件哈希校验。相同 Turnip ZIP 在 APK 真机重复导入于确认后的约 19–59 秒之间完成，成功提示、暂存清理与原有文件及配置哈希均已核对。这是轮询时间范围，不是精确基准。真实主机 shell 测试覆盖批量写入的字节一致性，以及失败后不发布、保留索引并清理暂存；原始 Turnip 与多依赖 Qualcomm ZIP 的主机导入测试通过。

APK 和 KernelSU WebUI 均已验证元数据声明 arm64-v8a、但主库实际为纯文本的测试 ZIP：导入被拒绝，页面显示“不支持 arm64-v8a”，没有新增索引或遗留暂存目录，索引与应用配置哈希未变。此包没有分配给任何应用，也没有执行。更新后的 WebUI 已从 KernelSU 模块列表正常入口重新打开，首页渲染与模块状态正常；通过系统文件选择器重复导入原始 Turnip ZIP 后显示“驱动已就绪”，暂存清理、索引与绑定配置哈希未变。

完整 Release ZIP `ci-180-7593c49` 已构建，真实主机 shell 的 arm64 安装测试确认模块及两份 hook 库均被解压与校验。随后通过 KernelSU 命令行覆盖安装并重启真机，启用版本为 `ci (180-7593c49-release)`，配置与驱动索引哈希未变。Unity 新进程映射私有 Turnip 主库和 Release hook 库，窗口显示 `Turnip Adreno (TM) 750` 与棋盘场景。安装前的模块目录、配置与索引保存在设备数据目录 `validation-backup-ci180`，没有替换驱动数据。

未选中的 DevCheck 冷启动只映射系统 Vulkan，没有模块私有驱动或 helper。通过 APK 关闭模块总开关后，配置明确为 `enabled=false` 且保留 Unity 的绑定；重启 Unity 后仅映射系统 Vulkan，场景正常渲染。横屏自动化点击曾未确认落盘，因此另行在竖屏复测关闭与重新启用：两次点击均确认配置变化，重新启用后配置哈希与测试前一致。Unity 冷启动的新进程记录 `Loaded`，映射私有 Turnip 主库和 Release hook 库，画面显示 `Turnip Adreno (TM) 750` 与正常渲染的棋盘场景。测试结束恢复了原来的自动旋转设置。

这些结果不代表所有应用或驱动兼容。使用 Turnip 时，Unity 启动日志仍报告 `VK_QCOM_fragment_density_map_offset` 缺少所需 `VK_EXT_fragment_density_map` 的扩展启用验证错误，未导致此次场景停止渲染，但不能称为无验证错误或完整 VRS 功能验收。配套 APK 的完整真机流程仍需继续验证。

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
