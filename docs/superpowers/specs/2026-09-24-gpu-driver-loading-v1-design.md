# 按 App 加载 GPU 驱动第一阶段设计

状态：Native 私有暂存、系统 Vulkan loader 拦截与 GPU 提交已在 Redmi Adreno 750 验证；WebUI 导入和真实 App 渲染验收继续进行

`libadrenotools` 的公开 `adrenotools_open_libvulkan()` 接口要求 hook 目录对应目标
App 的 `nativeLibraryDir`。本模块不调用该私有 loader 接口，而是直接复用
`hook_impl.cpp` 的 namespace 加载逻辑，并拦截 App 使用的系统 loader。Redmi
API 36 实测可以从 App 的私有目录加载 helper；此结论不代表其他 Android 版本
或设备已经兼容。

## 目标

第一阶段实现从本地 ZIP 导入驱动，并验证 Zygisk 在目标 App 进程内加载自定义 Vulkan 驱动的完整链路：

- 使用现有 WebUI 的 `targetPackages` 选择目标 App。
- 从本地选择 AdrenoTools 驱动 ZIP，解压并登记已安装驱动。
- 为每个目标 App 选择一个已安装驱动。
- 在 `preAppSpecialize()` 阶段完成校验和暂存，在 `postAppSpecialize()` 阶段安装系统 Vulkan loader 的 PLT 拦截。
- 只支持 Android 9+、arm64-v8a、Qualcomm Adreno、Vulkan。
- 失败时保留系统默认 Vulkan 驱动，不影响未选中的 App。

网络下载、Release API、驱动镜像、Qualcomm 提取驱动和非 Adreno 设备均不属于本阶段。

## 驱动输入

驱动 ZIP 由用户通过 WebUI 的本地文件选择器导入。导入过程不修改模板仓库，也不要求仓库内包含第三方二进制。

导入后，驱动以独立目录保存：

```text
/data/adb/<moduleId>/drivers/<driverId>/
├── meta.json
└── <libraryName from meta.json>
```

同时维护驱动索引，记录驱动 ID、显示名称、库名、ABI、导入时间和原始文件哈希。`meta.json` 使用 AdrenoTools 约定，至少包含 `libraryName` 字段。

ZIP 导入必须先进入临时目录，再校验压缩包路径、`meta.json`、驱动库文件名和 ABI，最后以原子改名方式安装。拒绝符号链接、绝对路径和 `../` 路径，避免 ZIP 路径穿越。

导入失败不能修改当前已安装驱动，也不能改变 App 当前绑定。

## Native 加载流程

```text
preAppSpecialize()
  ↓
读取 nice_name，去掉 :remote 等进程后缀
  ↓
从 companion 读取 config.json
  ↓
判断 enabled、targetPackages 和 packageSettings[package].driverId
  ↓
如果没有 driverId，则不安装自定义驱动
  ↓
在访问 companion 驱动 registry 前读取 KGSL GPU 型号：优先 `/sys/kernel/gpu/gpu_model`，缺失或不可读时尝试 `/sys/class/kgsl/kgsl-3d0/gpu_model`
  ↓
只有型号明确标识 Adreno 才继续；缺失、未知或非 Adreno 均回退，不在 zygote 初始化 Vulkan
  ↓
companion 校验 registry 文件，通过 zygote 创建的匿名内存文件返回只读快照
  ↓
pre 阶段校验 meta.json、libraryName 和 arm64 ELF
  ↓
请求 root companion 再次核对当前包绑定与 UID/GID，在 App 私有 files/<moduleId>/ 下原子暂存驱动与 helper
  ↓
postAppSpecialize() 安装系统 libvulkan.so 的 android_load_sphal_library PLT hook
  ↓
App 请求 Vulkan 驱动时进入 AdrenoTools hook_android_load_sphal_library
  ↓
核对返回句柄的 Vulkan 入口或 HMI 所在文件的设备号和 inode，记录加载或系统回退状态
```

普通 App 与 zygote 均不直接打开 `/data/adb` 的驱动文件。即便由 root 打开后
传递 FD，SELinux 仍可能拒绝 zygote 读取 `adb_data_file`。预校验使用由 zygote
创建、companion 写入、返回后封存的 memfd，避免放宽 SELinux。

磁盘暂存也由 root companion 执行：请求必须匹配当前配置的包和驱动绑定，UID/GID
必须匹配应用目录；不接受调用方指定任意路径。优先使用对应用户的 CE 目录，首次解锁前
CE 不存在时使用同一用户、同一包的 DE 目录。应用进程在 specialize 后只访问自己的
私有副本。每个 UID 的暂存发布串行执行，避免同一包的主进程和子进程冷启动竞争。

复制逻辑拒绝符号链接，限制路径只能位于固定的 `gpu-driver` 子目录，并使用临时目录加原子改名，避免进程看到不完整的驱动文件。

`libadrenotools` 使用 `ADRENOTOOLS_DRIVER_CUSTOM`。本阶段暂不启用 file redirect 和 GPU memory mapping 功能，以减少权限和兼容性变量。

PPSSPP 自己从私有 loader 句柄解析 Vulkan 函数，普通目标 App 不会使用模块打开的私有句柄。
模块因此将 AdrenoTools 的 `hook_impl.cpp` 编入注入库，初始化进程生命周期内有效的
`HookImplParams`，并拦截系统 loader 的驱动请求，而不把 `adrenotools_open_libvulkan()`
返回非空当作目标 App 已换驱动的证明。

安装成功仅记录 `HookInstalled`。驱动请求返回空句柄时调用原系统函数回退；上游内部
回退也可能返回非空系统驱动句柄，因此只有返回句柄的 Vulkan 入口或 Android HAL 的
`HMI` 对应已校验的私有驱动文件才记录 `Loaded`。系统文件记录 `SystemFallback`；
入口来源不可确认时调用原系统加载函数，不把无法识别的非空句柄交给 loader。
这些状态描述加载，不证明逻辑设备创建或渲染成功。

## WebUI 与平台桥接

本地 ZIP 选择需要新增平台桥接，而不是在业务页面中直接依赖浏览器 API：

- wasm/KernelsSU WebUI 使用浏览器文件选择器读取本地 ZIP，再通过现有 root bridge 写入临时文件。
- Android APK 使用系统的 Storage Access Framework 选择文件，再由 Android 实现复制到 root 可访问的临时位置。
- 公共层只接收导入结果和已安装驱动列表，不暴露平台文件句柄。
- 大文件传输必须分块，不能把完整 ZIP 拼进单条 shell 命令。

驱动管理页面提供导入、删除和查看详情；App Profile 页面提供驱动绑定。删除当前正在使用的驱动前必须先解除绑定或切换到其他驱动。

## 构建与打包

- 将 `bylaws/libadrenotools` 固定为递归 Git submodule，并保留其 BSD-2-Clause 许可证。
- 同时构建 `adrenotools`、`libhook_impl.so` 和 `libmain_hook.so`；v1 不使用 `libfile_redirect_hook.so`。
- 仅 arm64 构建和打包 AdrenoTools 相关目标，其他 ABI 保持现有 Zygisk 模块行为。
- `customize.sh` 将 hook 库放入模块的 `zygisk/` 目录，将 `drivers/` 目录保留在模块根目录。
- 不把任何第三方驱动二进制提交到模板仓库；驱动只存在于设备的模块数据目录。

安装器按现有 SHA-256 校验流程解压两个 helper 到模块 `zygisk/` 目录，再由 companion
暂存到 App 私有目录。驱动及 helper 的 App 副本为 `0500`，元数据为 `0400`；复用
前检查内容、属主和权限，不覆盖 App 控制的不匹配文件。

## 配置与生命周期

- 继续使用现有 `targetPackages`，并在 `PackageSettings` 增加可选的 `driverId`。
- `driverId` 为空表示该 App 使用系统默认驱动，不触发自定义 Vulkan hook。
- `system_server` 和未选中的进程直接 `DLCLOSE_MODULE_LIBRARY`。
- 目标进程加载成功后不立即卸载主 Zygisk 库，避免破坏已安装的 linker hook；失败时保留系统默认驱动。
- 每个目标进程最多安装一次 loader，重复调用必须幂等。
- 目标 App 需要重启后才会使用新的驱动。

## 验证

2026-10-02，正式模块在 Redmi 23117RK66C、Android API 36、arm64、Adreno 750、
KernelSU 32601 / Zygisk Next 1.5.0 上通过以下验证。设备保持首次解锁前的锁屏状态。

- 探针 APK 只有 `libvulkan_trigger.so`，不含 AdrenoTools helper 或驱动，也不依赖临时 Zygisk 模块。
- companion 在 App DE 私有目录暂存 helper 和用户已有的 Qualcomm 762.46 驱动。
- 模块记录 `HookInstalled` 后，实际 driver request 记录 `Loaded`；版本日志为 `0762.46`。
- `/proc/<pid>/maps` 映射私有 driver 和 helper；候选 SHA-256 为 `db493626626a79e5c929ec7d333177aab17a456497a5ad6d2d8a66a8f338c8cb`，不同于未修改的系统 driver。
- `vkCreateInstance`、物理设备枚举、`vkCreateDevice`、`vkQueueSubmit`、fence 等待均成功。
- `vkCmdFillBuffer` 写入 4096 字节 `0xc0dec0de`，映射并回读全部字节一致。
- 暂存驱动在 hook 安装后不可用时，模块记录 `SystemFallback`，系统 762.36.1 仍能完成同样的 GPU 提交和回读。
- 冷缓存并发启动同一包的两个进程，不再因临时目录和发布竞争而使其中一个回退。

该驱动仍使用设备的 vendor 支持库，不代表完整替换 Qualcomm 全套库。此验收不证明
图形 pipeline、swapchain、窗口渲染或性能；真实 App 渲染与 WebUI 本地 ZIP 导入仍需
继续验证。

运行时日志在 pre-specialize 建立固定大小 memfd 共享队列，由 root companion 映射；
完成尺寸密封和映射后关闭两端 FD。目标进程在 post-specialize 及实际 driver request
回调中写入队列，不再调用仅在 pre-specialize 可用的 `connectCompanion()`，也不依赖
当前设备返回 false 的 `exemptFd()`。每条日志最多 4096 字节，每进程队列 8 条；队列
满或发送竞争时丢弃记录，保留 logcat，不阻塞 App。队列归属于建立它的进程；App
自行 fork 的子进程不复用父队列，也不标记父队列关闭，只保留 logcat 输出。

companion 最多管理 128 个队列，使用一个接收线程，进程退出后回收映射，无活跃
队列时休眠。memfd 必须为无目录链接、尺寸匹配的普通文件，并具有 grow/shrink/seal
密封，避免 root 映射后被截断。接收内容只写入既有 `module.log`，不提供文件路径或
命令接口。该设备已验证 `HookInstalled`、`Loaded` 与 `SystemFallback` 持久化，同时
GPU 提交和 4096 字节回读通过。WebUI 现有日志页读取此文件；页面呈现仍需实机验证。

主机测试入口为 `sh scripts/test-native.sh`。安装检查为
`sh scripts/test-module-install.sh <module-debug.zip>`，会运行包内实际安装脚本和校验。

1. 构建 arm64 Debug 模块，检查 ZIP 中包含主 Zygisk 库和两个 hook 库，不包含第三方驱动二进制。
2. 在 WebUI 和 Android APK 各导入一个合法的 AdrenoTools ZIP，确认索引和目录内容一致。
3. 导入包含路径穿越、缺少 `meta.json`、缺少库文件和错误 ABI 的 ZIP，确认全部拒绝且不影响已有驱动。
4. 在 Android 9+ arm64 Adreno 真机绑定一个目标 App，确认未选中的 App 未安装 hook，目标 App 记录了驱动路径、`libraryName` 和加载结果。
5. 确认目标 App 能正常创建 Vulkan instance 和枚举物理设备。
6. 删除或破坏已绑定驱动，确认目标 App 能回退到系统驱动且不会导致启动崩溃。
7. 在没有驱动目录、非 arm64、非目标包、`enabled=false`、KGSL 型号缺失/未知及非 Adreno 型号时验证安全退出。
