# KsuWebUIStandalone compatibility

[KsuWebUIStandalone](https://github.com/NKU100/KsuWebUIStandalone) lets Magisk users run a module's WebUI without the KernelSU manager. This template uses the host's KernelSU-compatible JavaScript bridge and virtual resources.

## Host contract

| Surface | Expected behavior |
|---------|-------------------|
| `moduleInfo()` | Returns module metadata, including `id`, `moduleDir`, `updateJson`, `enabled`, `update`, and `remove`. |
| `listPackages(type)` | Returns package names for `user`, `system`, or `all`. |
| `getPackagesInfo(packageNamesJson)` | Accepts a JSON array of package names and returns metadata including `packageName`, `versionName`, `versionCode`, `appLabel`, `isSystem`, and `uid`. |
| `enableEdgeToEdge(enable)` | Enables or disables edge-to-edge layout and updates the inset resources. |
| `fullScreen(enable)` | Toggles full-screen mode and edge-to-edge layout. |
| `exit()` | Closes the WebUI activity. |
| `ksu://icon/{packageName}` | Serves the package icon as a PNG. The WebUI falls back to a letter icon when unavailable. |
| `internal/insets.css` | Enables inset handling and returns CSS variables for the current window insets. |
| `internal/colors.css` | Returns empty CSS; the standalone host does not provide KernelSU's dynamic Material You colors. |

## Insets

KernelSU's `--safe-area-inset-*` values are density-independent CSS pixels. The host divides Android system-bar insets by display density and truncates to integers for CSS and JavaScript, while keeping native WebView margins in physical pixels. The WebUI consumes these CSS values directly; dividing them by `devicePixelRatio` again makes the top and bottom padding too small.

The standalone host and this template must follow the same inset contract. Older host builds that inject physical pixels need to be updated alongside the template.

## Validation

The bridge and virtual resources were checked in an emulator running Magisk 30700 with root access granted. Validation covered module metadata, edge-to-edge toggling, package listing and metadata, activity exit, package icon responses, and inset CSS values. The empty `internal/colors.css` response is expected.
