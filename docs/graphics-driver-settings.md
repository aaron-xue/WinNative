# 图形驱动配置说明

> 覆盖「容器设置 / 快捷方式设置 → 显示（Display）」中的 **图形驱动（Graphics Driver）**、**Vulkan 显示同步（Vulkan Display Sync）** 以及 **图形驱动配置（Graphics Driver Configuration）** 卡片内的全部选项。
>
> 数据来源：`Container.java` 的默认配置串、`ContainerSettingsComposeDialog.kt` 的读写逻辑、`XServerDisplayActivity.java` 中把配置翻译成环境变量的部分，以及 `arrays.xml` 中的候选值。
>
> 配套文档：[DX 包装器配置说明](dx-wrapper-settings.md)（决定 D3D → Vulkan/GL 的转换层，与本页的 Vulkan 侧设置相互独立）。

---

## 一、配置是怎么存、怎么生效的

### 存储格式

所有选项拼成一条 **`key=value` 以分号 `;` 分隔** 的字符串，存在容器的 `graphicsDriverConfig` 字段里；快捷方式（游戏设置）可以用同名的 `graphicsDriverConfig` 覆盖容器值。

解析 / 序列化由 `GraphicsDriverConfigUtils` 完成（`split(";")` → `split("=", 2)`）。

### 默认值

`Container.DEFAULT_GRAPHICSDRIVERCONFIG`：

```
vulkanVersion=1.4;version=;blacklistedExtensions=;maxDeviceMemory=0;presentMode=mailbox;
syncFrame=0;disablePresentWait=0;resourceType=auto;bcnEmulation=auto;bcnEmulationType=compute;
bcnEmulationCache=0;gpuName=Device;transcoder=cpu;astcTranscoding=off
```

> 注意 `compositorPresentMode` **不在默认串里**，缺失时按 `fifo` 处理（容器与快捷方式两条代码路径的兜底值一致）。

### 生效链路

```
UI 选择
  → buildGraphicsDriverConfigFromState()  拼成 graphicsDriverConfig 字符串
  → 下次启动容器时 XServerDisplayActivity 读取并 parse
  → 逐项写入容器的 Wine 环境变量
  → Vulkan wrapper（libvulkan / Mesa turnip-zink）读取环境变量
```

启动时会先用默认配置兜底，再用实际配置覆盖，因此**老容器缺失的键会自动拿到默认值**：

```java
this.graphicsDriverConfig = parse(Container.DEFAULT_GRAPHICSDRIVERCONFIG);
this.graphicsDriverConfig.putAll(parse(graphicsDriverConfig));
```

### 三处可改

| 入口 | 代码位置 |
|------|----------|
| 容器设置 | `ContainerSettingsComposeDialog.kt` → `loadGraphicsDriverConfigState()` / `buildGraphicsDriverConfigFromState()` |
| 快捷方式（可覆盖容器） | `ShortcutSettingsComposeDialog.kt` → 同名函数，写入 `graphicsDriverConfig` 覆盖项 |
| 游戏内快捷设置 | `GameSettings.kt` → `GraphicsDriverConfigCard()`（同一批 state） |

---

## 二、图形驱动（Graphics Driver）

| 属性 | 值 |
|------|-----|
| 存储字段 | `graphicsDriver`（**独立字段**，不在 `graphicsDriverConfig` 串里） |
| 默认 | `Wrapper` |
| 可选值 | `Wrapper`, `Wrapper-Leegao`, `Wrapper-Gamenative` |

决定启动容器时解压哪一套 Vulkan wrapper（`graphics_driver/*.tzst`）：

| 选项 | 解压资源 | 差异 |
|------|----------|------|
| `Wrapper` | `graphics_driver/wrapper.tzst` | 标准 wrapper |
| `Wrapper-Leegao` | `graphics_driver/wrapper-leegao.tzst` | 额外把 `usr/lib` 作为 Adrenotools hooks 路径（`ADRENOTOOLS_HOOKS_PATH`） |
| `Wrapper-Gamenative` | `graphics_driver/wrapper-gamenative.tzst` | 只有这一套才有 **BCn Transcoder** 与 **ASTC Transcoding** 两个选项（见下文） |

- 切换驱动会检查 `usr/lib/.wrapper_state` 标记（内容为 `<stock|leegao|gamenative>:<versionCode>`），不一致时重新解压，避免每次启动都解包。
- 非 ARM64EC 时，DXVK / ddraw 相关条目里的 `arm64ec` 版本会被过滤掉。

---

## 三、图形驱动配置卡片

### 1. Vulkan 版本（Vulkan Version）

| 属性 | 值 |
|------|-----|
| 键名 | `vulkanVersion` |
| 类型 | 单选 |
| 默认 | `1.4` |
| 可选值 | `1.1`, `1.2`, `1.3`, `1.4` |
| 环境变量 | `WRAPPER_VK_VERSION` |

向 guest 声明的 Vulkan 次版本号。运行时会做两件事：

1. 读取驱动真实版本，补上 patch 段（如 `1.4` → `1.4.305`）；
2. 若驱动实际 minor 低于所选值，会**自动降级（clamp）**到驱动支持的最高 minor，并在日志里打印 `Clamping Vulkan …`。

调低版本可兼容只认旧 Vulkan 的游戏 / DXVK；调高可能让游戏启用更多特性，也可能踩到驱动未实现的路径。

---

### 2. 图形驱动版本（Graphics Driver Version）

| 属性 | 值 |
|------|-----|
| 键名 | `version` |
| 类型 | 单选 |
| 默认 | *空*（等于 `System`，即系统自带 Vulkan 驱动） |
| 候选来源 | `wrapper_graphics_driver_version_entries`（`System`）+ 已安装的 Adrenotools / Turnip 驱动 |
| 环境变量 | `ADRENOTOOLS_DRIVER_PATH` / `ADRENOTOOLS_DRIVER_NAME` / `ADRENOTOOLS_HOOKS_PATH` |

- 候选列表由 `GraphicsDriverCatalog.supportedVersions()` 生成：先按 `GPUInformation.isDriverSupported()` 过滤内置版本，再并入 `AdrenotoolsManager.enumerateInstalledDrivers()`；一个都不支持时退化为 `System`。
- 选择非 `System` 的驱动时，启动时通过 `AdrenotoolsManager.setDriverById()` 注入自定义驱动。
- 同一个值还用于给合成器挑选匹配的 `libvulkan`（保证导入的 AHB tiling 与生产者一致）。
- 切换版本会**重新枚举可用扩展**（见下一条），黑名单随版本变化。

---

### 3. 可用扩展（Available Extensions）

| 属性 | 值 |
|------|-----|
| 键名 | `blacklistedExtensions` |
| 类型 | 多选（勾掉 = 拉黑） |
| 默认 | 空（全部启用） |
| 存储 | 逗号分隔的扩展名 |
| 环境变量 | `WRAPPER_EXTENSION_BLACKLIST` |

- 列表来自 `GPUInformation.enumerateExtensions(version)`，即**当前所选驱动**支持的 Vulkan 扩展。
- 标题显示 `已启用 / 总数`。
- 用途：某些游戏在遇到特定扩展时会崩溃或走错渲染路径（典型如 `VK_EXT_descriptor_indexing`、`VK_KHR_buffer_device_address` 等），把对应扩展取消勾选即可屏蔽给 guest。
- 换驱动版本后黑名单会重置（因为只在同一版本下复用旧名单）。

---

### 4. GPU 名称（GPU Name）

| 属性 | 值 |
|------|-----|
| 键名 | `gpuName` |
| 类型 | 单选 |
| 默认 | `Device` |
| 环境变量 | `WRAPPER_DEVICE_NAME` / `WRAPPER_DEVICE_ID` / `WRAPPER_VENDOR_ID` |

- 候选是 `Device` 加上 `gpu_cards.json` 里的一批真实显卡名。
- `Device` 表示**透传设备真实 GPU 信息**，不做伪装。
- 选中具体显卡名时，会一并写入对应的 device id / vendor id，用于骗过按显卡名分支的游戏或驱动检查。
- **例外**：当 DXVK 版本为 `1.11.1-sarek` 时不会写入（该版本不兼容伪装）。

---

### 5. 显存上限（界面英文 label：Video Memory Limit，旧称 Max Device Memory）

| 属性 | 值 |
|------|-----|
| 键名 | `maxDeviceMemory` |
| 类型 | 单选（MB） |
| 默认 | `0`（不限制） |
| 可选值 | `0 (Default)`, `512`, `1024`, `2048`, `4096`, `8192`, `12288`, `16384` MB |
| 环境变量 | `WRAPPER_VMEM_MAX_SIZE` |

- 语义：交给 Vulkan wrapper 的**显存（device memory / VRAM）上限**，单位 MB。`VMEM` 即 video memory。
- 只有 `> 0` 时才写入环境变量；`0` 时**不写入任何变量**，由 wrapper 与驱动自行管理（代码：`XServerDisplayActivity` 约 9805 行）。
- 该值只是把 guest 能看到与能分配的显存压到上限，**不会凭空多出显存**；设得过小可能导致分配失败、贴图缺失或游戏拒绝启动。
- 与 WineD3D 的「显存大小」（`videoMemorySize` → `WINE_D3D_CONFIG` 的 `VideoMemorySize`）作用层面不同：后者在 WineD3D 侧上报，本项作用于 Vulkan wrapper 层。

---

### 6. 呈现模式（Present Modes）

| 属性 | 值 |
|------|-----|
| 键名 | `presentMode` |
| 类型 | 单选 |
| 默认 | `mailbox` |
| 可选值 | `mailbox`, `fifo`, `immediate`, `relaxed` |
| 环境变量 | `MESA_VK_WSI_PRESENT_MODE`，`immediate` 时额外写 `WRAPPER_MAX_IMAGE_COUNT=1` |

这是 **guest 侧（Wine 内）** 交换链的呈现模式：

| 选项 | 说明 |
|------|------|
| `mailbox` | 类快速垂直同步，队列里只留最新帧，延迟低、不撕裂（默认，兼顾） |
| `fifo` | 严格垂直同步，帧率被锁到刷新率，延迟最高但最稳 |
| `immediate` | 不同步，延迟最低，会撕裂；同时把 image count 压到 1 |
| `relaxed` | FIFO 的放宽版，超时后允许提前呈现，避免掉帧时卡顿加剧 |

---

### 7. Vulkan 显示同步（Vulkan Display Sync）

| 属性 | 值 |
|------|-----|
| 键名 | `compositorPresentMode` |
| 类型 | 单选 |
| 默认 | `fifo`（默认串缺该键时的兜底值） |
| 可选值 | `fifo`, `mailbox`, `immediate`（UI 显示 FIFO / Mailbox / Immediate） |
| 作用对象 | `VulkanRenderer.setPresentMode()` —— **App 自己的合成器**，而非 guest |

> 位置在「显示」区（与图形驱动、DX 包装器同排），但写入的是同一个 `graphicsDriverConfig` 串。

控制最终画面上屏时的同步方式，和上一项（guest 交换链）是两层：

| 选项 | 说明 |
|------|------|
| `fifo` | 按刷新率上屏，最稳，可能有 1 帧额外延迟 |
| `mailbox` | 有新帧就替换，延迟低 |
| `immediate` | 立即上屏，延迟最低，可能撕裂 |

---

### 8. 内存资源类型（Memory Resource Type）

| 属性 | 值 |
|------|-----|
| 键名 | `resourceType` |
| 类型 | 单选 |
| 默认 | `auto` |
| 可选值 | `auto`, `dmabuf`, `ahb`, `opaque` |
| 环境变量 | `WRAPPER_RESOURCE_TYPE` |

决定 guest 与 host 之间图像/缓冲内存的导入导出方式：

| 选项 | 说明 |
|------|------|
| `auto` | 由 wrapper 自行挑选（默认，最省事） |
| `dmabuf` | 走 dma-buf，通用性好 |
| `ahb` | 走 Android Hardware Buffer，某些设备上零拷贝更省 |
| `opaque` | 不透明句柄，兼容性兜底 |

出现花屏、绿屏、纹理错乱时可逐个切换排查。

---

### 9. BCn 模拟（BCn Emulation）

| 属性 | 值 |
|------|-----|
| 键名 | `bcnEmulation` |
| 类型 | 单选 |
| 默认 | `auto` |
| 可选值 | `none`, `partial`, `full`, `auto` |
| 环境变量 | `WRAPPER_EMULATE_BCN`（`0`/`1`/`2`/`3`），配合 `ENABLE_BCN_COMPUTE` / `BCN_COMPUTE_AUTO` |

移动 GPU 大多不支持桌面 BCn（DXTn）压缩纹理，需要软件/计算着色器解压：

| 选项 | `WRAPPER_EMULATE_BCN` | 说明 |
|------|----------------------|------|
| `none` | `0` | 不模拟，BCn 纹理可能显示错误或直接崩溃 |
| `partial` | `1` | 只模拟必要的部分，开销最小 |
| `full` | `2` | 全量模拟，兼容性最好，开销最大 |
| `auto` | `3` | 交给 wrapper 按情况决定（默认） |

- 只有 `auto` / `full` 且模拟类型为 `compute` 时才会启用计算着色器路径：
  - `auto` → `ENABLE_BCN_COMPUTE=1`, `BCN_COMPUTE_AUTO=1`（由 wrapper 判断）
  - `full` → `ENABLE_BCN_COMPUTE=1`, `BCN_COMPUTE_AUTO=0`（强制全量）
- **自动排除**：Adreno（vendorId `20803`）以及 `Wrapper-Gamenative` + Xclipse（vendorId `0x144D`）上强制不走 compute 路径 —— 前者不支持，后者会崩。
- 选 `none` 时，下面的「BCn 模拟类型」「BCn 模拟缓存」两个选项在 UI 里会隐藏。

---

### 10. BCn 模拟类型（BCn Emulation Type）

| 属性 | 值 |
|------|-----|
| 键名 | `bcnEmulationType` |
| 类型 | 单选 |
| 默认 | `compute` |
| 可选值 | `software`, `compute` |
| 显示条件 | `bcnEmulation != none` |

| 选项 | 说明 |
|------|------|
| `software` | CPU 软解，兼容性最好，占用 CPU、速度慢 |
| `compute` | 用计算着色器在 GPU 上解压，速度快（默认） |

仅在 BCn 模拟为 `auto` / `full` 时影响是否启用 `ENABLE_BCN_COMPUTE`；`partial` 不受此项影响。

---

### 11. BCn 模拟缓存（BCn Emulation Cache）

| 属性 | 值 |
|------|-----|
| 键名 | `bcnEmulationCache` |
| 类型 | 开关（0/1） |
| 默认 | `0` |
| 可选值 | `0`（关）, `1`（开） |
| 环境变量 | `WRAPPER_USE_BCN_CACHE` |
| 显示条件 | `bcnEmulation != none` |

把解压后的纹理缓存下来，重进游戏/重复出现的贴图不必再解一次，能显著降低重复开销；代价是占用更多磁盘/内存。

---

### 12. BCn 转码器（BCn Transcoder）

| 属性 | 值 |
|------|-----|
| 键名 | `transcoder` |
| 类型 | 单选 |
| 默认 | `cpu` |
| 可选值 | `cpu`, `gpu` |
| 环境变量 | `WRAPPER_BCN_GPU`（`gpu` → `1`，否则 `0`） |
| 显示条件 | 图形驱动 = `Wrapper-Gamenative` |

| 选项 | 说明 |
|------|------|
| `cpu` | BCn 转码在 CPU 上做（默认，稳） |
| `gpu` | 转码放到 GPU 上，速度快但依赖 gamenative wrapper 的实现 |

#### 它到底在做什么

PC 游戏的纹理绝大多数是 **BCn（DXTn / BC1–BC7）块压缩**格式，而移动 GPU 只认 **ASTC / ETC2** 这类块格式，两者互不兼容。处理这条路有两条：

| 路线 | 做法 | 代价 |
|------|------|------|
| **BCn 模拟**（上一项） | 把 BCn 块**解压成未压缩的 RGBA** 再交给 GPU | 显存占用暴涨（BC1 是 1/8 压缩比，解成 RGBA 就是 8 倍），采样带宽高 |
| **BCn 转码**（本项） | 把 BCn 块**直接翻译成 GPU 原生压缩块**，GPU 采样时仍是压缩纹理 | 省显存、省带宽，但要额外做一次格式转换 |

简单说：**模拟 = 解成裸像素（能看，但费显存）；转码 = 换成另一种压缩格式（能看，且仍是压缩纹理）**。转码只在 `Wrapper-Gamenative` 这套 wrapper 里实现，所以只有选它时才会出现。

- `cpu`：转换走 CPU，每次纹理上传都要过一遍，稳但占 CPU，纹理多时会看到卡顿尖峰。
- `gpu`：转换放到 GPU 上做，大纹理 / 批量贴图场景优势明显，但依赖 gamenative 的实现，个别设备上可能翻车（花屏、色偏），出问题就退回 `cpu`。

---

### 13. ASTC 转码（ASTC Transcoding）

| 属性 | 值 |
|------|-----|
| 键名 | `astcTranscoding` |
| 类型 | 单选 |
| 默认 | `off` |
| 可选值 | `off`, `4x4`, `8x8`（UI 显示 Off / Fidelity / Performance） |
| 环境变量 | `WRAPPER_BCN_ASTC` + `WRAPPER_ASTC_BLOCK` |
| 显示条件 | 图形驱动 = `Wrapper-Gamenative` |

| 选项 | 说明 |
|------|------|
| `off` | 关闭 ASTC 转码 |
| `4x4`（Fidelity） | 4×4 块，画质更好、开销更大 |
| `8x8`（Performance） | 8×8 块，性能更好、画质略降 |

只有 `4x4` / `8x8` 会被接受，其它值一律按关闭处理。

#### 它到底在做什么

这一项决定 BCn 纹理**转码成哪种 ASTC 块**。ASTC 是按固定大小的像素块压缩的，块越大压缩率越高、越省显存和带宽，但块内能保留的细节越少：

| 块尺寸 | 每块像素 | 码率 | 画质 | 开销 |
|--------|----------|------|------|------|
| `4x4` | 16 px | 1 字节/像素（8bpp） | 最好，接近无损 | 显存 / 带宽占用最大 |
| `8x8` | 64 px | 0.5 字节/像素（2bpp） | 细节损失明显 | 最省显存 / 带宽，采样最快 |

- 画质优先（UI 文字、法线贴图、锐利边缘多的游戏）选 `4x4`。
- 显存紧张、贴图量巨大，或明显感觉到纹理上传卡顿时选 `8x8`；代价是法线贴图和细密纹理上容易出现块状瑕疵。
- 关掉则不做 ASTC 转码，退回「BCn 模拟」那套路径。

> 三项的层级关系：**BCn 模拟**决定"要不要处理 BCn 纹理"，**BCn 转码器**决定"在 CPU 还是 GPU 上转换"，**ASTC 转码**决定"转成什么规格的原生压缩块"。后两项只在 `Wrapper-Gamenative` 下生效。

---

### 14. 每帧同步（Sync Every Frame）

| 属性 | 值 |
|------|-----|
| 键名 | `syncFrame` |
| 类型 | 开关（0/1） |
| 默认 | `0` |
| 环境变量 | `MESA_VK_WSI_DEBUG=forcesync` |

开启后强制每帧都做同步，画面/时序更"正确"，能修掉部分渲染错乱与时序问题，但会明显掉帧。仅作排查手段，不建议常开。

---

### 15. 禁用 KHR_present_wait（Disable KHR_present_wait）

| 属性 | 值 |
|------|-----|
| 键名 | `disablePresentWait` |
| 类型 | 开关（0/1） |
| 默认 | `0` |
| 环境变量 | `WRAPPER_DISABLE_PRESENT_WAIT` |

关闭 `VK_KHR_present_wait` 扩展的使用。该扩展用于精确控制呈现时机，部分设备/驱动实现有问题时会导致卡顿、掉帧或帧率异常，此时可打开此项绕开。

---

## 四、环境变量对照表

启动时由 `XServerDisplayActivity` 写入，供 Vulkan wrapper 读取：

| 环境变量 | 来源键 | 说明 |
|----------|--------|------|
| `WRAPPER_VK_VERSION` | `vulkanVersion` | 声明的 Vulkan 版本（含 patch 段，必要时自动降级） |
| `WRAPPER_EXTENSION_BLACKLIST` | `blacklistedExtensions` | 逗号分隔的扩展黑名单 |
| `WRAPPER_DEVICE_NAME` / `_ID` / `VENDOR_ID` | `gpuName` | 伪装 GPU 名（非 `Device` 且非 sarek DXVK 时） |
| `WRAPPER_VMEM_MAX_SIZE` | `maxDeviceMemory` | 显存（device memory）上限 MB，>0 才写 |
| `MESA_VK_WSI_PRESENT_MODE` | `presentMode` | guest 交换链呈现模式 |
| `WRAPPER_MAX_IMAGE_COUNT` | `presentMode` | `immediate` 时置 1 |
| `WRAPPER_RESOURCE_TYPE` | `resourceType` | 内存资源类型 |
| `MESA_VK_WSI_DEBUG` | `syncFrame` | 置 `forcesync` |
| `WRAPPER_DISABLE_PRESENT_WAIT` | `disablePresentWait` | 是否禁用 present_wait |
| `WRAPPER_EMULATE_BCN` | `bcnEmulation` | `0/1/2/3` = none/partial/full/auto |
| `ENABLE_BCN_COMPUTE` / `BCN_COMPUTE_AUTO` | `bcnEmulation` + `bcnEmulationType` | 计算着色器 BCn 路径 |
| `WRAPPER_USE_BCN_CACHE` | `bcnEmulationCache` | BCn 解压缓存 |
| `WRAPPER_BCN_GPU` | `transcoder` | 仅 gamenative：GPU 转码 |
| `WRAPPER_BCN_ASTC` / `WRAPPER_ASTC_BLOCK` | `astcTranscoding` | 仅 gamenative：ASTC 转码 |
| `ADRENOTOOLS_DRIVER_*` / `_HOOKS_PATH` | `version` | 自定义 Turnip 驱动（非 System 时） |

另有固定写入的 `VK_ICD_FILENAMES=.../vulkan/icd.d/wrapper_icd.aarch64.json` 与 `GALLIUM_DRIVER=zink`，不受本页选项控制。

---

## 五、调参建议

| 症状 | 优先尝试 |
|------|----------|
| 贴图发黑/花屏/缺失 | BCn 模拟 `auto` → `full`；仍不行再换 `partial` + 类型 `software` |
| BCn 模拟开启后掉帧 | 开「BCn 模拟缓存」；BCn 类型保持 `compute`；转码器 `gpu`（gamenative） |
| 贴图糊、出现块状瑕疵（gamenative） | ASTC 转码选 `4x4`（Fidelity） |
| 显存吃紧 / 纹理上传卡顿（gamenative） | ASTC 转码选 `8x8`（Performance），BCn 转码器选 `gpu` |
| 帧率被锁 60 / 延迟高 | guest 呈现模式 `mailbox` 或 `immediate`；显示同步 `Mailbox` |
| 画面撕裂 | 呈现模式改回 `fifo`，显示同步改回 `FIFO` |
| 卡顿、帧时间抖动 | 打开「禁用 KHR_present_wait」；内存资源类型换 `ahb` / `dmabuf` |
| 游戏认不到显卡 / 报不支持 | GPU 名称指定具体显卡；Vulkan 版本降到 `1.2` / `1.3` |
| 显存杀手爆内存 | 设「最大设备内存」为 2048 / 4096 |
| 装了驱动但没生效 | 检查「图形驱动版本」是否选中该驱动；Adreno 上 BCn compute 会被自动禁用（日志 `Clamping Vulkan` / `GraphicsDriverExtraction`） |
| 打开就崩 | 在「可用扩展」里逐个拉黑可疑扩展；BCn 模拟先设 `none` 排除 |

排错时可以看 logcat 的 `GraphicsDriverExtraction` 标签，它会打印实际使用的驱动、Vulkan 版本降级、BCn / ASTC 开关情况。

---

## 六、代码索引

| 内容 | 位置 |
|------|------|
| 默认配置串 | `app/src/main/runtime/container/Container.java` → `DEFAULT_GRAPHICSDRIVERCONFIG` |
| 解析 / 序列化 | `app/src/main/feature/settings/drivers/GraphicsDriverConfigUtils.java` |
| 驱动版本与扩展枚举 | `app/src/main/runtime/system/GraphicsDriverCatalog.kt`、`GPUInformation`、`AdrenotoolsManager` |
| 候选值数组 | `app/src/main/res/values/arrays.xml`（`vulkan_version_entries`、`device_memory_entries`、`present_mode_entries`、`compositor_present_mode_entries`、`resource_type_entries`、`bcn_emulation*`、`wrapper_transcoder_entries`、`wrapper_astc_transcoding_*`） |
| 容器设置读写 | `app/src/main/feature/settings/containers/ContainerSettingsComposeDialog.kt` → `loadGraphicsDriverConfigState()` / `buildGraphicsDriverConfigFromState()` |
| 快捷方式覆盖 | `app/src/main/feature/shortcuts/ShortcutSettingsComposeDialog.kt`（同名函数） |
| 游戏内 UI | `app/src/main/feature/library/GameSettings.kt` → `GraphicsDriverConfigCard()` |
| 翻译成环境变量 | `app/src/main/runtime/display/XServerDisplayActivity.java` → `extractGraphicsDriverFiles()`（约 9639–9880 行） |
| 合成器呈现模式 | `app/src/main/runtime/display/renderer/VulkanRenderer.java` → `parsePresentMode()` |
