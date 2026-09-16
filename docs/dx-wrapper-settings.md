# DX 包装器配置说明（DX Wrapper）

> 覆盖「容器设置 / 快捷方式设置 → 显示（Display）」中的 **DX 封装器（DX Wrapper）**，以及跟随它切换的 **DXVK 配置** 与 **WineD3D 配置** 两张卡片。
>
> 数据来源：`Container.java` 的默认配置串、`DXVKConfigUtils` / `WineD3DConfigUtils`、`ContainerSettingsComposeDialog.kt` 的读写逻辑、`XServerDisplayActivity.java` 的解压与环境变量写入部分，以及 `arrays.xml` 的候选值。
>
> 配套文档：[图形驱动配置说明](graphics-driver-settings.md)（决定 Vulkan 侧行为，本页决定 Direct3D → 什么 API 的转换层）。

---

## 一、配置是怎么存、怎么生效的

### 两个字段

| 字段 | 内容 | 默认 |
|------|------|------|
| `dxwrapper` | 用哪套包装器：`WineD3D` / `DXVK+VKD3D` | `dxvk+vkd3d` |
| `dxwrapperConfig` | 包装器的详细参数，**`key=value` 以逗号 `,` 分隔** | 见下 |

> 注意分隔符：这里是 **逗号**，而图形驱动配置用的是 **分号 `;`**。解析由 `KeyValueSet` 完成（`DXVKConfigUtils.parseConfig` / `WineD3DConfigUtils.parseConfig`，空串时回落默认串）。

### 默认配置串

`Container.DEFAULT_DXWRAPPERCONFIG`：

```
version=,async=1,asyncCache=1,vkd3dVersion=None,vkd3dLevel=12_1,ddrawrapper=none,
csmt=3,gpuName=NVIDIA GeForce GTX 480,videoMemorySize=4096,strict_shader_math=1,
OffscreenRenderingMode=fbo,renderer=gl
```

新建容器时不直接用这个常量，而是 `ContainerCreation.defaultDxWrapperConfig()` 按**已安装的 DXVK / VKD3D 内容包**自动挑版本（并按是否 ARM64EC 过滤 `arm64ec` 条目）。

### 两张卡片共用同一个串

UI 上 DXVK 卡片与 WineD3D 卡片**二选一显示**（`GameSettings.kt`：选中项含 `dxvk` 就显示 DXVK 卡片，否则显示 WineD3D 卡片），但它们写入的是**同一个 `dxwrapperConfig`**：

| 卡片 | 写入的键 |
|------|----------|
| DXVK 配置 | `version`, `async`, `asyncCache`, `vkd3dVersion`, `vkd3dLevel`, `ddrawrapper` |
| WineD3D 配置 | `csmt`, `gpuName`, `videoMemorySize`, `strict_shader_math`, `OffscreenRenderingMode`, `renderer` |

两边不会互相删除对方的键，但 **`gpuName` 这个键名被两边共用**（`buildWineD3DConfigFromState()` 写它，`DXVKConfigUtils` 不读它）。想伪装 GPU 时：

- 走 DXVK 路线 → 生效的是 **图形驱动配置里的 GPU 名称**（`WRAPPER_DEVICE_NAME`）；
- 走 WineD3D 路线 → 生效的是 **WineD3D 卡片里的 GPU 名称**（`WINE_D3D_CONFIG` 的 `VideoPciDeviceID`）。

### 生效链路

```
启动时 XServerDisplayActivity
  ├─ dxwrapper 含 "dxvk"  → DXVKConfigUtils.setEnvVars()   + 解压 DXVK / VKD3D / ddrawrapper 文件
  └─ 否则（WineD3D）      → WineD3DConfigUtils.setEnvVars() 写 WINE_D3D_CONFIG
```

启动时 `dxwrapper` 会被重新拼成 `dxvk版本;vkd3d版本;ddraw包装` 三段（`XServerDisplayActivity` 约 7696 行），供后续按段解压。

快捷方式可以用 `dxwrapper` / `dxwrapperConfig` 覆盖容器值。

---

## 二、DX 封装器（DX Wrapper）

| 属性 | 值 |
|------|-----|
| 存储字段 | `dxwrapper` |
| 默认 | `DXVK+VKD3D` |
| 可选值 | `WineD3D`, `DXVK+VKD3D` |

| 选项 | 转换路径 | 特点 |
|------|----------|------|
| `WineD3D` | D3D → OpenGL / Vulkan（Wine 自带实现） | 兼容性最好，老游戏、DirectDraw、2D 游戏友好；性能通常低于 DXVK |
| `DXVK+VKD3D` | D3D9/10/11 → Vulkan（DXVK），D3D12 → Vulkan（VKD3D） | 性能好、着色器编译可异步/可缓存；对驱动和 Vulkan 特性要求更高 |

两条路都会固定写入 `GALLIUM_DRIVER=zink`（即 OpenGL 部分最终也走 Zink over Vulkan）。

---

## 三、DXVK 配置卡片

### 1. DXVK 版本（DXVK Version）

| 属性 | 值 |
|------|-----|
| 键名 | `version` |
| 类型 | 单选 |
| 默认 | 空（`None`，即不注入 DXVK） |
| 候选来源 | `dxvk_version_entries`（`None`）+ 已安装的 DXVK 内容包（`CONTENT_TYPE_DXVK`） |
| 运行时 | 按名称找内容包并 `applyContent()`；未安装则只打警告日志 |

- 非 ARM64EC 容器会自动过滤掉名字里带 `arm64ec` 的条目。
- 版本名同时决定后面两个异步开关能否使用（见第 5、6 项）：只有名字含 `async` / `gplasync` 的版本才支持异步。
- 选了某个 VKD3D 版本后，DXVK 列表会被过滤成 **≥ 2.x** 的条目（`handleDxvkVkd3dVersionChanged()`），因为老 DXVK 与新 VKD3D 不匹配。
- 特殊版本 `1.11.1-sarek` 会额外写入 `WRAPPER_NO_PATCH_OPCONSTCOMP=1`（关掉 wrapper 的 SPIR-V 补丁），并会让图形驱动里的 GPU 名伪装失效。

---

### 2. VKD3D 版本（VKD3D Version）

| 属性 | 值 |
|------|-----|
| 键名 | `vkd3dVersion` |
| 类型 | 单选 |
| 默认 | `None` |
| 候选来源 | `vkd3d_version_entries`（`None`）+ 已安装的 VKD3D 内容包（`CONTENT_TYPE_VKD3D`） |
| 运行时 | `None` → 恢复 Wine 内置 `d3d12.dll`；否则 `applyVkd3dWrapper()` 注入所选版本 |

只有 **D3D12 游戏**用得上。选非 `None` 的同时，DXVK 版本会被限制到 2.x 以上（见上一条）。

---

### 3. VKD3D 功能级别（VKD3D Feature Level）

| 属性 | 值 |
|------|-----|
| 键名 | `vkd3dLevel` |
| 类型 | 单选 |
| 默认 | `12_1` |
| 可选值 | `12_0`, `12_1`, `12_2`, `11_1`, `11_0`, `10_1`, `10_0`, `9_3`, `9_2`, `9_1` |
| 环境变量 | `VKD3D_FEATURE_LEVEL` |

向游戏声明支持到哪个 D3D 功能级别。D3D12 游戏起不来或报"不支持的功能级别"时，可以按游戏要求下调（例如 `12_0`、`11_1`）；反过来，级别调太低会让游戏拒绝启用高级特效甚至拒绝启动。

---

### 4. DDraw 封装器（DDraw Wrapper）

| 属性 | 值 |
|------|-----|
| 键名 | `ddrawrapper` |
| 类型 | 单选 |
| 默认 | `none` |
| 候选来源 | `ddrawrapper_entries`（`CnC-DDraw`、`Dd7To9`、`ddraw-4.21`、`ddraw-11.8`、`None`）+ 已安装的 D7VK 内容包 |
| 环境变量 | `CNC_DDRAW_CONFIG_FILE`（仅 `cnc-ddraw`） |

服务老 DirectDraw / Direct3D 7 及更早的游戏（多数是 2000 年前后的老游戏）。启动时处理顺序：

1. 总会先解压 `ddrawrapper/nglide.tzst`（Glide 兼容层）；
2. 清掉上一次遗留的 `ddraw_.dll`；
3. 判断选中项：

| 选中 | 行为 |
|------|------|
| 匹配到已安装的 D7VK 内容包 | 应用 D7VK，并把原 `ddraw.dll` 备份为 `ddraw_.dll` 作为透传 |
| `none` | 恢复 Wine 内置 `ddraw.dll` / `d3dimm.dll` |
| `cnc-ddraw` | 额外设置 `CNC_DDRAW_CONFIG_FILE=C:\windows\syswow64\ddraw.ini`，再解压该包 |
| 其它（`Dd7To9`、`ddraw-4.21`、`ddraw-11.8`） | 解压 `ddrawrapper/<名称>.tzst` 到 Windows 目录 |

- 非 ARM64EC 时，名字含 `arm64ec` 的条目会被过滤。
- 老游戏黑屏、无法全屏、鼠标错位、色盘错误时，逐个试这几项。

---

### 5. 启用异步（Enable Async）

| 属性 | 值 |
|------|-----|
| 键名 | `async` |
| 类型 | 开关（0/1） |
| 默认 | `1` |
| 环境变量 | `DXVK_ASYNC=1` |
| 可用条件 | 所选 DXVK 版本名含 `async` 或 `gplasync`，否则 UI 置灰 |

开启后着色器编译不再阻塞渲染 —— 这是消除"首次进入场景卡几秒"的主要手段。代价是着色器没编译完的瞬间可能出现画面缺失/闪烁，个别反作弊严格的在线游戏也可能不接受。

### 6. 启用异步缓存（Enable Async Cache）

| 属性 | 值 |
|------|-----|
| 键名 | `asyncCache` |
| 类型 | 开关（0/1） |
| 默认 | `1` |
| 环境变量 | `DXVK_GPLASYNCCACHE=1` |
| 可用条件 | 所选 DXVK 版本名含 **`gplasync`**，否则 UI 置灰 |

把异步编译出来的 GPL 着色器缓存到磁盘（缓存目录由 `DXVK_STATE_CACHE_PATH` 指向 imagefs 的 cache 目录），第二次启动同一场景就不会再卡。建议常开。

---

## 四、WineD3D 配置卡片

这一卡片的全部选项最终合并成一条 **`WINE_D3D_CONFIG`** 环境变量：

```
csmt=0x<值>,strict_shader_math=0x<值>,OffscreenRenderingMode=<值>,VideoMemorySize=<MB>,
VideoPciDeviceID=<id>,VideoPciVendorID=<id>,renderer=<值>
```

### 1. CSMT（命令流多线程）

| 属性 | 值 |
|------|-----|
| 键名 | `csmt` |
| 类型 | 启用 / 禁用 |
| 默认 | 启用（写入 `3`） |
| 写入形式 | `csmt=0x3` / `csmt=0x0` |

把 D3D 命令流的提交放到独立线程，避免主线程被驱动阻塞，通常能明显提升帧率。极少数老游戏会在开启后花屏或崩溃，此时关掉。

### 2. GPU 名称（GPU Name）

| 属性 | 值 |
|------|-----|
| 键名 | `gpuName` |
| 类型 | 单选 |
| 默认 | `NVIDIA GeForce GTX 480` |
| 候选来源 | `gpu_cards.json` |
| 写入形式 | `VideoPciDeviceID` / `VideoPciVendorID` |

用所选显卡的 deviceID / vendorID 向游戏伪装硬件，解决"显卡不被识别/报不支持"的问题。

> 已知瑕疵：`WineD3DConfigUtils.setEnvVars()` 里 vendorID 取自 `config.get("vendorID")`，而配置串里并没有 `vendorID` 这个键（只有 `gpuName`），因此 `VideoPciVendorID` 实际会是空值。deviceID 走的是 `gpuName`，正常。

### 3. 显存大小（Video Memory Size）

| 属性 | 值 |
|------|-----|
| 键名 | `videoMemorySize` |
| 类型 | 单选（MB） |
| 默认 | `4096` |
| 可选值 | `32`, `64`, `128`, `256`, `512`, `1024`, `2048`, `4096` MB |
| 写入形式 | `VideoMemorySize=<MB>` |

向游戏报告的显存容量。一些老游戏按这个值选纹理档位；报太大会让它们尝试加载超出内存的贴图。

### 4. 严格着色器数学（Strict Shader Math）

| 属性 | 值 |
|------|-----|
| 键名 | `strict_shader_math` |
| 类型 | 启用 / 禁用 |
| 默认 | 启用（写入 `1`） |
| 写入形式 | `strict_shader_math=0x1` / `0x0` |

要求着色器按 IEEE 规范严格计算，画面/光照更接近 PC 原版；关掉后驱动可以走更快但不精确的近似路径，能提帧，代价是可能出现光照/雾效差异。

### 5. 离屏渲染模式（Offscreen Rendering Mode）

| 属性 | 值 |
|------|-----|
| 键名 | `OffscreenRenderingMode` |
| 类型 | 单选 |
| 默认 | `fbo` |
| 可选值 | `fbo`, `backbuffer` |

| 选项 | 说明 |
|------|------|
| `fbo` | 渲染到 FBO（帧缓冲对象）再合成，兼容性最好（默认） |
| `backbuffer` | 直接渲染到后缓冲区，部分游戏更快，在 `fbo` 下花屏/黑屏时可试 |

### 6. 渲染器（Renderer）

| 属性 | 值 |
|------|-----|
| 键名 | `renderer` |
| 类型 | 单选 |
| 默认 | `gl` |
| 可选值 | `gl`, `vulkan`, `gdi` |

WineD3D 的后端：

| 选项 | 说明 |
|------|------|
| `gl` | OpenGL（默认，实际经 `GALLIUM_DRIVER=zink` 落到 Vulkan） |
| `vulkan` | WineD3D 的 Vulkan 后端，部分场景更快，兼容性弱于 `gl` |
| `gdi` | 走 GDI，仅用于极老的 2D 软件渲染兜底 |

---

## 五、环境变量对照表

| 环境变量 | 来源 | 说明 |
|----------|------|------|
| `DXVK_ASYNC` | `async` | DXVK 异步着色器编译 |
| `DXVK_GPLASYNCCACHE` | `asyncCache` | GPL 异步着色器缓存 |
| `VKD3D_FEATURE_LEVEL` | `vkd3dLevel` | D3D12 功能级别 |
| `DXVK_STATE_CACHE_PATH` | 固定 | DXVK 状态缓存目录（imagefs 下 cache 目录） |
| `WINE_D3D_CONFIG` | WineD3D 卡片全部项 | csmt / 显存 / 严格着色器 / 离屏模式 / 渲染器 / PCI ID |
| `CNC_DDRAW_CONFIG_FILE` | `ddrawrapper=cnc-ddraw` | CnC-DDraw 配置文件路径 |
| `WRAPPER_NO_PATCH_OPCONSTCOMP` | `version=1.11.1-sarek` | 关闭 wrapper 的 SPIR-V OpConstantComposite 补丁 |
| `GALLIUM_DRIVER` | 固定 | 恒为 `zink` |

DXVK / VKD3D / D7VK 本体不是靠环境变量生效的，而是启动时把对应内容包（`.tzst` / 内容管理）解到 Wine 前缀的 `system32` / `syswow64` 里覆盖 DLL。

---

## 六、选型与调参建议

| 症状 | 优先尝试 |
|------|----------|
| 现代 D3D11/12 游戏帧率低 | 用 `DXVK+VKD3D`，装好 DXVK 2.x + 需要的 VKD3D 版本 |
| 老游戏 / DirectDraw 游戏黑屏、花屏、色盘错 | 换 `WineD3D`；再逐个试 DDraw 封装器（`CnC-DDraw` → `Dd7To9` → `ddraw-4.21` → `ddraw-11.8`） |
| 首次进场景 / 放技能卡几秒 | 开启「启用异步」+「启用异步缓存」（需要带 `gplasync` 的 DXVK 版本） |
| D3D12 游戏起不来 / 报功能级别不支持 | VKD3D 功能级别从 `12_1` 调到 `12_0` 或 `11_1` |
| WineD3D 下花屏 | 离屏渲染模式 `fbo` → `backbuffer` |
| WineD3D 下帧率低 | CSMT 保持启用；渲染器 `gl` → `vulkan`；关「严格着色器数学」 |
| 游戏提示显存不足 / 贴图档位太低 | 调 WineD3D 的「显存大小」或图形驱动的「显存上限」 |
| 装了 DXVK 却没生效 | 确认「DXVK 版本」选中了它（不是 `None`），且内容包已安装；非 ARM64EC 容器看不到 `arm64ec` 版本 |
| 换装 VKD3D 后 DXVK 列表变短 | 正常：选了非 `None` 的 VKD3D 后，DXVK 只保留 ≥ 2.x |

排查时看 logcat 的 `XServerDisplayActivity` 标签，会打印实际使用的 `dxvk / vkd3d / ddrawrapper` 三段，以及内容包未安装时的告警。

---

## 七、代码索引

| 内容 | 位置 |
|------|------|
| 默认值 | `app/src/main/runtime/container/Container.java` → `DEFAULT_DXWRAPPER` / `DEFAULT_DXWRAPPERCONFIG` / `DEFAULT_DDRAWRAPPER` |
| 新容器默认值推断 | `app/src/main/runtime/container/ContainerCreation.kt` → `defaultDxWrapperConfig()` |
| DXVK 解析与环境变量 | `app/src/main/feature/settings/drivers/DXVKConfigUtils.java` |
| WineD3D 解析与环境变量 | `app/src/main/feature/settings/drivers/WineD3DConfigUtils.java` |
| GPU 显卡表 | `gpu_cards.json`（`AssetPaths.GPU_CARDS`） |
| 候选值数组 | `app/src/main/res/values/arrays.xml`（`dxwrapper_entries`、`dxvk_version_entries`、`vkd3d_version_entries`、`ddrawrapper_entries`、`video_memory_size_entries`） |
| 容器设置读写 | `app/src/main/feature/settings/containers/ContainerSettingsComposeDialog.kt` → `loadDxvkConfigState()` / `buildDxvkConfigFromState()` / `loadWineD3DConfigState()` / `buildWineD3DConfigFromState()` |
| 快捷方式覆盖 | `app/src/main/feature/shortcuts/ShortcutSettingsComposeDialog.kt`（同名函数） |
| 游戏内 UI | `app/src/main/feature/library/GameSettings.kt` → `DXVKConfigCard()` / `WineD3DConfigCard()`（按 `selectedDxWrapper` 二选一） |
| 解压与注入 | `app/src/main/runtime/display/XServerDisplayActivity.java` → `extractGraphicsDriverFiles()`（约 9648 行分流）与 ddraw 段（约 10440–10530 行） |
