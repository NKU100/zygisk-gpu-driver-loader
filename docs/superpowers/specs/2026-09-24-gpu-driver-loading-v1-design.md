# 按 App 加载 GPU 驱动第一阶段设计

状态：范围已确认；Native hook 路径待设备验证

实现前置约束：`libadrenotools` 的公开接口要求 `hookLibDir` 指向目标 App 的
`nativeLibraryDir`。Zygisk 模块目录、目标 App 私有目录和该目录不是同一个路径，
因此“将 hook 库复制到 App 私有目录后直接调用”目前只能作为待验证假设，不能视为
最终实现。第一道 Native 验证必须在真实的 arm64 Adreno 设备上确认 hook 库的可见路径；
如果该路径不可行，需要先调整加载策略，再继续完整 UI 和驱动导入实现。

## 目标

第一阶段实现从本地 ZIP 导入驱动，并验证 Zygisk 在目标 App 进程内加载自定义 Vulkan 驱动的完整链路：

- 使用现有 WebUI 的 `targetPackages` 选择目标 App。
- 从本地选择 AdrenoTools 驱动 ZIP，解压并登记已安装驱动。
- 为每个目标 App 选择一个已安装驱动。
- 在 `preAppSpecialize()` 阶段安装 `libadrenotools` 的 Vulkan 拦截。
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
通过 Api::getModuleDir() 读取模块内的 hook 与已安装 driver 文件
  ↓
将驱动复制到 app_data_dir/files/<moduleId>/gpu-driver/；按真实设备验证结果准备 hook 库路径
  ↓
校验 meta.json、libraryName 和目标 ABI
  ↓
调用 adrenotools_open_libvulkan(... ADRENOTOOLS_DRIVER_CUSTOM ...)
  ↓
记录成功或回退原因
```

模块目录只能在 `preAppSpecialize()` 阶段安全访问。驱动复制目标使用 `app_data_dir` 下的应用私有目录，并设置为目标 App 的 UID/GID，避免 Vulkan 驱动在进程完成 specialize 后再次访问 `/data/adb` 路径。hook 库不能直接套用这个结论，必须满足 `libadrenotools` 对 `nativeLibraryDir` 的要求，具体暂存或挂载策略由真实设备验证决定。

复制逻辑拒绝符号链接，限制路径只能位于固定的 `gpu-driver` 子目录，并使用临时目录加原子改名，避免进程看到不完整的驱动文件。

`libadrenotools` 使用 `ADRENOTOOLS_DRIVER_CUSTOM`。本阶段暂不启用 file redirect 和 GPU memory mapping 功能，以减少权限和兼容性变量。

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

由于 `libadrenotools` 的公开 API 文档要求 hook 目录对应目标 App 的 `nativeLibraryDir`，真实设备验证必须确认 Zygisk 场景如何提供这个路径；如果某些 Android 版本拒绝模块目录或 App 私有目录，必须在实现阶段调整 hook 库暂存策略，而不是放宽到外部存储。

## 配置与生命周期

- 继续使用现有 `targetPackages`，并在 `PackageSettings` 增加可选的 `driverId`。
- `driverId` 为空表示该 App 使用系统默认驱动，不触发自定义 Vulkan hook。
- `system_server` 和未选中的进程直接 `DLCLOSE_MODULE_LIBRARY`。
- 目标进程加载成功后不立即卸载主 Zygisk 库，避免破坏已安装的 linker hook；失败时保留系统默认驱动。
- 每个目标进程最多安装一次 loader，重复调用必须幂等。
- 目标 App 需要重启后才会使用新的驱动。

## 验证

1. 构建 arm64 Debug 模块，检查 ZIP 中包含主 Zygisk 库和两个 hook 库，不包含第三方驱动二进制。
2. 在 WebUI 和 Android APK 各导入一个合法的 AdrenoTools ZIP，确认索引和目录内容一致。
3. 导入包含路径穿越、缺少 `meta.json`、缺少库文件和错误 ABI 的 ZIP，确认全部拒绝且不影响已有驱动。
4. 在 Android 9+ arm64 Adreno 真机绑定一个目标 App，确认未选中的 App 未安装 hook，目标 App 记录了驱动路径、`libraryName` 和加载结果。
5. 确认目标 App 能正常创建 Vulkan instance 和枚举物理设备。
6. 删除或破坏已绑定驱动，确认目标 App 能回退到系统驱动且不会导致启动崩溃。
7. 在没有驱动目录、非 arm64、非目标包、`enabled=false`、KGSL 型号缺失/未知及非 Adreno 型号时验证安全退出。
