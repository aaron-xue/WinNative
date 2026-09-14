# 组件安装清单（`.yml`）说明

本文描述 WinNative「安装组件」功能使用的组件清单文件格式。行为依据
`app/src/main/runtime/content/component/ComponentInstaller.kt`（执行器）与
`app/src/main/feature/settings/containers/ComponentInstallerSheet.kt`（界面/目录）。

---

## 1. 清单是什么

一个组件 = **一个 YAML 清单文件（`.yml`）** + 清单引用的若干文件（安装包 / 压缩包 / DLL / 字体）。
清单由一串 `Steps` 组成，安装器按顺序把它们作用到**某个容器的 Wine 前缀**上
（`容器目录/.wine/drive_c`）。

清单有两种来源：

| 来源 | 说明 |
|-|-|
| 在线目录 | 界面从 `https://hf-mirror.com/datasets/Xnick417x/WN-Components/resolve/main/index.json` 拉取目录，用户点「Install」后再按条目的 `manifest` 字段下载对应 `.yml` |
| 本地安装 | 用户选择 `.wcp` / `.xz` / `.txz` / `.tzst` 包，包先被解到缓存目录，然后取包内**第一个 `.yml`** 作为清单（递归查找，取首个） |

两种方式的差别只有一点：本地安装时 `skipDownload = true`，**下载阶段被整体跳过**，
清单引用的文件必须已经由包提供。

---

## 2. 顶层结构

安装器只读取顶层键 `Steps`：

```yaml
Steps:
  - action: download_archive
    ...
  - action: archive_extract
    ...
  - action: copy_dll
    ...
```

- `Steps` 必须是**步骤对象的列表**；缺失或为空 → 安装失败（`Manifest has no steps`）。
- 其他顶层键（例如 `Name`、`Description`、`Version`）当前**不被解析**，可当作注释/备注自由书写；
  展示用的名称与分类来自在线目录的 `index.json`，不来自清单本身。
- 解析使用 SnakeYAML，标准 YAML 1.1 语法；`action` 的值区分大小写（全部小写 + 下划线）。

---

## 3. 执行流程

安装器把 `Steps` 跑三遍，每遍只处理自己关心的 action：

```
阶段 1  下载      download_archive / archive_extract / cab_extract / install_exe / install_msi
阶段 2  解包      archive_extract / cab_extract / get_from_cab
阶段 3  应用      copy_dll / copy_file / link_dir / override_dll / set_register_key / delete_dlls
                  install_exe / install_msi / register_font / replace_font / install_fonts
                  install_cab_fonts / register_dll   （uninstall 跳过，未知 action 仅记日志）
```

所以**步骤顺序对下载/解包/应用三类动作之间没有影响**，但同类动作之间按书写顺序执行
（例如先 `copy_dll` 再 `override_dll` 才是正确的阅读顺序）。

三个阶段之间有中断检查：用户关闭安装面板导致的线程中断会抛 `Cancelled`。
结束后无论成功失败都会清理 `drive_c/wn-install` 与解包临时目录。

成功收尾：在 `容器目录/.wn-components/<组件名>` 写入时间戳文件，
界面据此把该组件显示为「Installed」。

---

## 4. 目录约定（路径模板）

### 4.1 源路径 `url`（在 `copy_*` / `link_dir` 步骤中表示“源目录”）

| 写法 | 解析结果 |
|-|-|
| `temp/xxx` | 解包工作目录 `installed/xxx`（即 `archive_extract` 的产物） |
| 其它相对路径 | 先找 `组件缓存目录/xxx`；不存在则退回到 `installed/xxx` |

> 缓存目录 = `context.cacheDir/wn-components/<组件名>/`，
> 下载得到的文件与本地包解出来的文件都在这里。

### 4.2 目标路径 `dest`

| 写法 | 解析结果 |
|-|-|
| `temp/xxx` | 解包工作目录（用于把文件放到中间目录） |
| `windows/xxx` | `drive_c/windows/xxx` |
| `win32` / `win32/xxx` | `drive_c/windows/syswow64/...`（32 位） |
| `win64` / `win64/xxx` | `drive_c/windows/system32/...`（64 位） |
| 其它 | 原样当文件系统路径使用（相对路径依赖进程 CWD，**不推荐**） |

---

## 5. 通用字段

| 字段 | 用于 | 说明 |
|-|-|-|
| `action` | 全部 | 动作名，必填；未知值只写日志不报错 |
| `url` | 下载类 + `copy_*`/`link_dir` | 下载地址，或（复制步骤里）源目录 |
| `file_name` | 下载类 / 复制类 | 文件名；复制步骤里支持 `*`、`?` 通配 |
| `rename` | 下载类 / 安装类 | 落盘后使用的文件名，优先于 `file_name` |
| `file_checksum` | 下载类 | **MD5** 小写十六进制；已缓存且校验一致则跳过下载，不一致直接失败 |
| `dest` | 解包 / 复制 / 删除 | 目标目录，见 4.2 |
| `source` | `get_from_cab` | CAB 源路径，文件名允许含 `*` |
| `arguments` | `install_exe` / `install_msi` | 传给安装程序的命令行参数 |

其他细节：

- 下载 URL 中的 `huggingface.co` 会被自动替换为 `hf-mirror.com`。
- 通配转换规则：`*` → `.*`，`?` → `.`，忽略大小写，整名匹配。

---

## 6. 动作详解

### `download_archive`

下载一个文件到组件缓存目录，文件名取 `rename ?: file_name`。

```yaml
- action: download_archive
  url: https://example.com/pkg.tar.xz
  file_name: pkg.tar.xz
  file_checksum: 0cc175b9c0f1b6a831c399e269772661
```

### `archive_extract`

把缓存目录里的包解到 `installed/<去掉最后一级扩展名的文件名>`。
按扩展名分派：`.zip`、`.tar.xz` / `.xz`、`.tar.zst` / `.zst` / `.tzst`，其他格式直接失败。

```yaml
- action: archive_extract
  file_name: pkg.tar.xz
  # 产物目录：temp/pkg.tar
```

### `cab_extract`

用系统镜像里的 `usr/bin/cabextract` 解 CAB 到 `dest`。

```yaml
- action: cab_extract
  file_name: dxnt.cab
  dest: temp/dxnt
```

### `get_from_cab`

从 CAB 中按文件名（可含 `*`，作为 `cabextract -F` 过滤）抽取到 `dest`。

```yaml
- action: get_from_cab
  source: temp/dxsdk/*.cab
  file_name: d3dx9_43.dll
  dest: win32
```

### `copy_dll` / `copy_file` / `link_dir`

三者当前**实现完全相同**：把 `url` 源目录下匹配 `file_name` 的文件复制到 `dest`。
`file_name` 含 `*` 时批量复制，一个都没匹配上只记日志不会失败；
非通配且文件不存在则失败。

```yaml
- action: copy_dll
  url: temp/pkg.tar/system32
  file_name: "*.dll"
  dest: win64
```

### `override_dll`

向 `user.reg` 的 `Software\Wine\DllOverrides` 写覆盖项。两种写法：

```yaml
# 单条
- action: override_dll
  dll: d3d9
  type: native,builtin     # 缺省 native,builtin

# 批量
- action: override_dll
  bundle:
    - value: d3d9
      data: native,builtin
    - value: dxgi
      data: native,builtin
```

### `set_register_key`

写注册表。`key` 以 `HKLM` 开头写 `system.reg`，否则写 `user.reg`；
实际子键为 `key` 中第一个 `\` 之后的部分。
`type` 仅支持 `REG_DWORD`（`data` 为整数）和 `REG_SZ`（`data` 为字符串），其他值不产生任何写入。

```yaml
- action: set_register_key
  key: HKCU\Software\Wine\Direct3D
  value: MaxVersionGL
  type: REG_DWORD
  data: 30002
```

### `delete_dlls`

按列表删除 `dest` 下的文件（用于清掉 Wine 自带的占位 DLL）。

```yaml
- action: delete_dlls
  dest: win64
  dlls:
    - d3d9.dll
    - dxgi.dll
```

### `install_exe` / `install_msi`

把安装程序复制到容器的 `C:\wn-install\`，然后在容器内启动 Wine 执行并等待退出。

- `install_exe`：直接执行该文件，参数取 `arguments`。
- `install_msi`：执行 `C:\windows\system32\msiexec.exe /i "<文件>" <arguments?:/quiet> /norestart`。
- 退出码 `0`、`3010`、`143` 视为成功，其余失败。
- 超时 20 分钟；若当前已有会话在跑，直接报「请先关闭会话」。
- 这类组件在界面上标记为 `species: installer`。

```yaml
- action: install_msi
  url: https://example.com/wine-mono.msi
  file_name: wine-mono.msi
  arguments: /quiet
```

### `register_font`

在 `system.reg` 的 `Software\Microsoft\Windows NT\CurrentVersion\Fonts` 登记字体。

```yaml
- action: register_font
  name: Arial (TrueType)
  file: arial.ttf
```

### `replace_font`

在 `user.reg` 的 `Software\Wine\Fonts\Replacements` 写替换项，取 `replace` 列表第一项。

```yaml
- action: replace_font
  font: Arial
  replace:
    - SimSun
```

### `install_fonts` / `install_cab_fonts`

把 `fonts` 列出的文件从源目录复制到 `drive_c/windows/Fonts`。
注意：源目录用 `url` 指定，但走的是 **dest 规则**（见 4.2）。

```yaml
- action: install_fonts
  url: temp/fonts
  fonts:
    - arial.ttf
    - arialbd.ttf
```

### `register_dll`

在容器内批量 `regsvr32 /s`。失败只写日志、不中断安装（regsvr32 退出码不可靠）。

```yaml
- action: register_dll
  dlls:
    - quartz.dll
    - l3codecx.ax
```

### 不支持 / 忽略的动作

| action | 行为 |
|-|-|
| `set_windows`、`use_windows` | 直接失败：`Needs 'xxx' — not supported yet.` |
| `uninstall` | 跳过（安装器自行处理覆盖安装） |
| 其它未知 action | 仅 `Log.w`，跳过 |

---

## 7. 完整示例

```yaml
Name: DXVK 1.12 (示例)
Description: Vulkan-based D3D translation layer

Steps:
  # 1. 下载并解包
  - action: download_archive
    url: https://huggingface.co/datasets/Xnick417x/WN-Components/resolve/main/dxvk-1.12.tar.xz
    file_name: dxvk-1.12.tar.xz
    file_checksum: 5eb63bbbe01eeed093cb22bb8f5acdc3

  - action: archive_extract
    file_name: dxvk-1.12.tar.xz      # 解到 temp/dxvk-1.12.tar

  # 2. 清掉 wine 自带实现，放入 DLL
  - action: delete_dlls
    dest: win64
    dlls: [d3d9.dll, dxgi.dll, d3d10core.dll, d3d11.dll]

  - action: copy_dll
    url: temp/dxvk-1.12.tar/x64
    file_name: "*.dll"
    dest: win64

  - action: copy_dll
    url: temp/dxvk-1.12.tar/x32
    file_name: "*.dll"
    dest: win32

  # 3. 注册 DLL 覆盖
  - action: override_dll
    bundle:
      - { value: d3d9,      data: native }
      - { value: dxgi,      data: native }
      - { value: d3d11,     data: native }
      - { value: d3d10core, data: native }
```

---

## 8. 本地安装（`.wcp` / `.txz` / `.tzst` / `.xz`）要点

- 包先被解到 `cacheDir/wn-components/<包名>/`，再取其内第一个 `.yml`。
- 因为 `skipDownload = true`，**下载阶段整体跳过**，但**解包阶段照常执行**，
  清单里若写 `archive_extract`，它会去 `组件缓存目录/<file_name>` 找文件——
  除非包内真的带着这个压缩包，否则会失败。
  ✅ 建议：本地包里放已经解好的文件，清单只用 `copy_dll` / `copy_file` / `override_dll` 等步骤。
- 复制步骤的 `url` 写相对目录即可，例如包内结构为 `system32/*.dll` 时：

```yaml
Steps:
  - action: copy_dll
    url: system32
    file_name: "*.dll"
    dest: win64
  - action: override_dll
    dll: d3d9
    type: native
```

- 组件名 = **包文件名（不含扩展名）**，界面与「已安装」标记都用它。

---

## 9. 在线目录条目（`index.json`）

目录是 `{"components": [ ... ]}`，每条支持：

| 字段 | 说明 |
|-|-|
| `name` | 组件名，必填；也是「已安装」标记的键 |
| `description` | 列表第二行文字 |
| `provider` | 提供方（当前未在界面展示） |
| `category` | 分类，缺省 `Other`；必须是 `Wine Mono / Gecko`、`.NET`、`Visual C++ / VB`、`DirectX`、`Data / Text`、`Fonts / GDI`、`System / Web`、`Graphics`、`Media / Codecs`、`OS Update`、`Other` 之一，否则排在最后 |
| `species` | `installer` 显示 `INSTALLER` 徽章，其它（如 `library`）显示 `LIBRARY` |
| `size` | 字节数，用于列表右下角体积显示 |
| `manifest` | 清单路径，拼接为 `https://hf-mirror.com/datasets/Xnick417x/WN-Components/resolve/main/<manifest>` |
| `dependencies` | 依赖组件名列表（当前仅解析，未自动安装） |

---

## 10. 常见失败原因

| 提示 | 原因 |
|-|-|
| Container isn't set up yet | 容器未初始化，先启动一次 |
| Download failed / Checksum mismatch | URL 不可达，或 `file_checksum` 与文件 MD5 不符 |
| Unsupported archive format | `archive_extract` 的扩展名不在支持列表内 |
| Missing file / Copy failed | 源目录或 `file_name` 与解包产物实际结构不符 |
| CAB not found / cabextract is missing | CAB 路径错，或系统镜像缺少 `usr/bin/cabextract` |
| A session is still running | 安装 `install_exe`/`install_msi`/`register_dll` 前必须先关闭容器会话 |
| Installer exited with code N | 安装程序返回了 0/3010/143 之外的退出码 |
| Installer timed out | 超过 20 分钟或会话被关闭 |
