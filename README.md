# Zygisk GPU Driver Loader

为指定应用加载自定义 Adreno Vulkan 驱动的 Zygisk 模块。支持导入原始 AdrenoTools 驱动 ZIP、按应用选择驱动，以及删除驱动时将相关应用恢复为系统驱动。

模块不附带 GPU 驱动，不提供在线下载，也不修改系统或 vendor 驱动文件。

## 使用条件

- Android 9 或更新版本、Qualcomm Adreno GPU、64 位 ARM 目标应用。
- Magisk 或 KernelSU，并启用可用的 Zygisk 实现。KernelSU 需要另外安装 Zygisk 实现。
- 目标应用通过系统 Vulkan loader 加载驱动。OpenGL ES、32 位应用和非 Adreno GPU 不在自定义驱动加载范围内。
- 自备与设备 GPU 和 Android 版本匹配的 AdrenoTools 驱动 ZIP。

能导入或加载驱动不代表应用一定能正常渲染。部分应用可能闪退、黑屏或出现图形错误，遇到问题请切回系统驱动并重启应用。

## 安装与使用

1. 从 [Releases](https://github.com/NKU100/zygisk-gpu-driver-loader/releases) 获取模块 ZIP，在 root 管理器中安装并重启设备。标记为 Pre-release 的版本属于测试构建。
2. KernelSU 用户从模块页面打开 WebUI。Magisk 用户可通过 [KsuWebUIStandalone](https://github.com/NKU100/KsuWebUIStandalone) 打开模块 WebUI，或使用[自行构建的配置 APK](docs/development.md)；为配置应用或 WebUI 宿主授予 root 权限。
3. 打开“设置 → GPU 驱动”，导入本地驱动 ZIP。
4. 在“应用”页面启用目标应用，选择导入的驱动，并确认模块总开关已开启。
5. 重启目标应用，结合模块日志与实际渲染结果确认效果。

发生异常时，选择“系统驱动”并重启目标应用。若配置界面无法打开，在 root 管理器中禁用模块并重启设备。

详细格式、删除行为、日志含义和兼容性限制见[使用指南](docs/gpu-driver-guide.md)。

## 开发与来源

- [构建与测试](docs/development.md)
- [模板同步说明](docs/template-sync.md)

项目基于 [Zygisk Module WebUI Template](https://github.com/NKU100/zygisk-module-webui-template)，使用 [AdrenoTools](https://github.com/bylaws/libadrenotools) 加载自定义驱动。导入的驱动由用户自行获取，其授权和兼容性由相应驱动项目决定。

## License

本仓库包含多个许可范围。请查看 [LICENSE](LICENSE) 和[第三方声明](THIRD_PARTY_NOTICES.md)，其中也说明了尚待确认许可的继承文件。
