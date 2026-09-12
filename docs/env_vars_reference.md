# 环境变量参考说明

> 以下环境变量定义来自 `EnvVarsView.java` 中的 `knownEnvVars`，用于在 Winlator 中配置 Wine / DXVK / Mesa 等运行时行为。

---

## 图形 / 渲染 相关

### ZINK_DESCRIPTORS
| 属性 | 值 |
|------|-----|
| 类型 | 单选 |
| 默认 | `auto` |
| 可选值 | `auto`, `lazy`, `cached`, `notemplates` |

控制 Zink（OpenGL over Vulkan）的描述符分配策略：

| 选项 | 说明 |
|------|------|
| `auto` | 自动选择最佳策略 |
| `lazy` | 延迟分配描述符 |
| `cached` | 缓存描述符以减少重复分配 |
| `notemplates` | 禁用描述符模板 |

---

### ZINK_DEBUG
| 属性 | 值 |
|------|-----|
| 类型 | 多选 |
| 可选值 | `nir`, `spirv`, `tgsi`, `validation`, `sync`, `compact`, `noreorder` |

Zink 调试选项，用于诊断 OpenGL 到 Vulkan 转换的问题：

| 选项 | 说明 |
|------|------|
| `nir` | 调试 NIR（中间表示）代码 |
| `spirv` | 调试 SPIR-V 着色器 |
| `tgsi` | 调试 TGSI（旧版着色器中间表示） |
| `validation` | 启用 Vulkan 验证层 |
| `sync` | 调试同步问题 |
| `compact` | 紧凑模式 |
| `noreorder` | 禁用指令重排序 |

---

### MESA_SHADER_CACHE_DISABLE
| 属性 | 值 |
|------|-----|
| 类型 | 复选框 |
| 默认 | `false` |
| 启用值 | `true` |

设置为 `true` 时禁用 Mesa 着色器缓存。某些游戏在着色器缓存不兼容时可能需要启用此项。

---

### mesa_glthread
| 属性 | 值 |
|------|-----|
| 类型 | 复选框 |
| 默认 | `false` |
| 启用值 | `true` |

启用 OpenGL 多线程调度，可将 GL 调用调度到后台线程以提升性能。对某些游戏的帧率有明显改善。

---

### MESA_EXTENSION_MAX_YEAR
| 属性 | 值 |
|------|-----|
| 类型 | 文本输入 |

限制 Mesa 报告的 OpenGL 扩展版本年份上限。用于模拟较老的 GPU 扩展支持级别，某些游戏可能需要此设置来兼容旧版特性。

---

### MESA_GL_VERSION_OVERRIDE
| 属性 | 值 |
|------|-----|
| 类型 | 文本输入 |

覆盖 OpenGL 版本号。用于让应用或游戏认为系统支持特定的 OpenGL 版本，例如设为 `4.6` 或 `3.3`。

---

### GALLIUM_HUD
| 属性 | 值 |
|------|-----|
| 类型 | 多选 |
| 可选值 | `simple`, `fps`, `frametime` |

启用在屏幕上叠加显示 Gallium 驱动的性能信息：

| 选项 | 说明 |
|------|------|
| `simple` | 显示简单的性能信息 |
| `fps` | 显示帧率 (FPS) |
| `frametime` | 显示帧时间 |

---

## Vulkan / DXVK / VKD3D 相关

### DXVK_HUD
| 属性 | 值 |
|------|-----|
| 类型 | 多选 |
| 可选值 | `scale=0.5`, `scale=0.7`, `opacity=0.5`, `opacity=0.7`, `devinfo`, `fps`, `frametimes`, `submissions`, `drawcalls`, `pipelines`, `descriptors`, `memory`, `gpuload`, `version`, `api`, `cs`, `compiler`, `samplers` |

DXVK 的 HUD（屏显信息），用于 DirectX→Vulkan 转换的调试和性能监控：

| 选项 | 说明 |
|------|------|
| `scale=0.5` / `0.7` | HUD 缩放比例 |
| `opacity=0.5` / `0.7` | HUD 透明度 |
| `devinfo` | 显示设备信息（GPU 名称、驱动版本等） |
| `fps` | 显示帧率 |
| `frametimes` | 显示帧时间图 |
| `submissions` | 显示提交数量 |
| `drawcalls` | 显示绘制调用次数 |
| `pipelines` | 显示图形管线统计 |
| `descriptors` | 显示描述符统计 |
| `memory` | 显示显存使用情况 |
| `gpuload` | 显示 GPU 负载 |
| `version` | 显示 DXVK 版本 |
| `api` | 显示使用的 API |
| `cs` | 显示计算着色器信息 |
| `compiler` | 显示着色器编译信息 |
| `samplers` | 显示采样器统计 |

---

### DXVK_DISABLE_TIMELINE_SEMAPHORES
| 属性 | 值 |
|------|-----|
| 类型 | 复选框 |
| 默认 | `0` |
| 启用值 | `1` |

禁用 DXVK 使用 Vulkan 时间线信号量（Timeline Semaphores）。在某些 GPU 驱动上可能出现同步问题，设置此项可以解决。

---

### VKD3D_SHADER_MODEL
| 属性 | 值 |
|------|-----|
| 类型 | 单选（可自定义） |
| 可选值 | `6_9`, `6_6`, `6_0`, `5_0` |

覆盖 VKD3D（Direct3D 12→Vulkan）报告的着色器模型版本：

| 选项 | 说明 |
|------|------|
| `6_9` | Shader Model 6.9（最新） |
| `6_6` | Shader Model 6.6 |
| `6_0` | Shader Model 6.0 |
| `5_0` | Shader Model 5.0（较老） |

---

## Wine 同步（Esync / NTsync）

### WINEESYNC
| 属性 | 值 |
|------|-----|
| 类型 | 复选框 |
| 默认 | `0` |
| 启用值 | `1` |

启用 Wine 的 Esync（Eventfd Synchronization），将 Wine 的同步对象重定向到 Linux eventfd，大幅减少多线程游戏的同步开销，提升性能。如果游戏创建大量线程后崩溃，可尝试禁用此项。

> ⚠️ **与 WINENTSYNC 互斥**，不要同时启用。

---

### WINENTSYNC
| 属性 | 值 |
|------|-----|
| 类型 | 复选框 |
| 默认 | `0` |
| 启用值 | `1` |

启用 Wine 的 NTsync（NT Synchronization），一种更新的同步机制，在 Esync 基础上提供更精确的 Windows NT 同步语义。

> ⚠️ **与 WINEESYNC 互斥**，不要同时启用。

---

### WINE_FAST_YIELD
| 属性 | 值 |
|------|-----|
| 类型 | 文本输入 |
| 默认 | `1` |

控制 Wine 的线程让步行为。设置为 `1` 时使用快速让步机制，能改善某些多核场景下的性能。

---

## Adreno GPU / Turnip 驱动 相关

### TU_DEBUG
| 属性 | 值 |
|------|-----|
| 类型 | 多选 |
| 可选值 | `forcecb`, `nocb`, `startup`, `deck_emu`, `nir`, `nobin`, `sysmem`, `gmem`, `forcebin`, `layout`, `noubwc`, `nomultipos`, `nolrz`, `nolrzfc`, `perf`, `perfc`, `flushall`, `syncdraw`, `push_consts_per_stage`, `rast_order`, `unaligned_store`, `log_skip_gmem_ops`, `dynamic`, `bos`, `3d_load`, `fdm`, `noconform`, `rd` |

Turnip Vulkan 驱动调试标志（主要用于高通 Adreno GPU）：

| 选项 | 说明 |
|------|------|
| `forcecb` | 强制使用常量缓冲区 |
| `nocb` | 禁用常量缓冲区 |
| `startup` | 显示启动调试信息 |
| `deck_emu` | Steam Deck 模拟模式 |
| `nir` | 调试 NIR 代码 |
| `nobin` | 禁用二进制着色器 |
| `sysmem` | 使用系统内存模式 |
| `gmem` | 使用 GMEM（片上内存）模式 |
| `forcebin` | 强制分箱（Binning）模式 |
| `layout` | 显示管线布局信息 |
| `noubwc` | 禁用 UBWC 压缩 |
| `nomultipos` | 禁用多位置输出 |
| `nolrz` | 禁用 LRZ |
| `nolrzfc` | 禁用 LRZ 快速清除 |
| `perf` | 性能调试信息 |
| `perfc` | 精简性能调试信息 |
| `flushall` | 强制刷新所有操作 |
| `syncdraw` | 同步绘制操作 |
| `push_consts_per_stage` | 每个阶段使用推送常量 |
| `rast_order` | 强制光栅化顺序 |
| `unaligned_store` | 允许非对齐存储 |
| `log_skip_gmem_ops` | 记录跳过的 GMEM 操作 |
| `dynamic` | 动态模式 |
| `bos` | 调试缓冲对象 |
| `3d_load` | 调试 3D 加载 |
| `fdm` | 调试 FDM |
| `noconform` | 禁用一致性 |
| `rd` | 调试渲染目标 |

---

### FD_DEV_FEATURES
| 属性 | 值 |
|------|-----|
| 类型 | 多选 |
| 可选值 | `enable_tp_ubwc_flag_hint=1`, `storage_8bit=1` |

控制 Freedreno 设备特性：

| 选项 | 说明 |
|------|------|
| `enable_tp_ubwc_flag_hint=1` | 启用 UBWC 压缩标志提示，可提升带宽利用率 |
| `storage_8bit=1` | 启用 8 位存储支持 |

---

### IR3_SHADER_DEBUG
| 属性 | 值 |
|------|-----|
| 类型 | 多选 |
| 可选值 | `nouboopt`, `nopreamble`, `noearlypreamble` |

Freedreno IR3 着色器编译器调试选项：

| 选项 | 说明 |
|------|------|
| `nouboopt` | 禁用 UBO（Uniform Buffer Object）优化 |
| `nopreamble` | 禁有着色前导码 |
| `noearlypreamble` | 禁用提前前导码 |

---

## Wine 音频 相关

### PULSE_LATENCY_MSEC
| 属性 | 值 |
|------|-----|
| 类型 | 数字输入 |

设置 PulseAudio 音频延迟（毫秒）。值越小延迟越低但可能出现爆音，值越大越稳定但延迟增加。默认值通常为 60ms。

---

### ALSA_LATENCY_MS
| 属性 | 值 |
|------|-----|
| 类型 | 数字输入 |

设置 ALSA 音频延迟（毫秒）。与 PulseAudio 延迟类似，影响音频的流畅度和响应性。

---

### ALSA_VOLUME
| 属性 | 值 |
|------|-----|
| 类型 | 小数输入 |

设置 ALSA 输出的音频增益（倍数）。例如 `1.0` 为原始音量，`0.5` 降低一半，`2.0` 翻倍。

---

### ALSA_BASS_BOOST
| 属性 | 值 |
|------|-----|
| 类型 | 小数输入 |

设置 ALSA 输出的低频增强（倍数）。用于加强低音效果。

---

### ALSA_PERFORMANCE_MODE
| 属性 | 值 |
|------|-----|
| 类型 | 单选 |
| 可选值 | `low_latency`, `none`, `power_saving` |

ALSA 性能模式：

| 选项 | 说明 |
|------|------|
| `low_latency` | 低延迟模式，适合游戏 |
| `none` | 默认模式 |
| `power_saving` | 省电模式，降低功耗但可能增加延迟 |

---

## Wine 兼容性 / 其他

### WINE_DESKTOP_CAPTURE
| 属性 | 值 |
|------|-----|
| 类型 | 复选框 |
| 默认 | `0` |
| 启用值 | `1` |

启用 Wine 桌面捕获功能。允许应用截取整个 Wine 桌面的屏幕内容。

---

### WRAPPER_MAX_IMAGE_COUNT
| 属性 | 值 |
|------|-----|
| 类型 | 文本输入 |

设置图形包装器的最大图像计数。用于控制交换链中缓冲区的最大数量，可影响渲染延迟。

---

### WRAPPER_DMAHEAP_CACHED
| 属性 | 值 |
|------|-----|
| 类型 | 复选框 |
| 默认 | `0` |
| 启用值 | `1` |

启用 DMA-Heap 缓存。在 Android 上使用缓存的 DMA 堆内存，可能改善 GPU 和 CPU 之间的数据传输性能。

---

### WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER
| 属性 | 值 |
|------|-----|
| 类型 | 复选框 |
| 默认 | `0` |
| 启用值 | `1` |

禁用 Wine 创建 DXGI 设备管理器。某些游戏在 Wine 创建 DXGI 设备管理器后会崩溃或渲染异常，启用此选项可避免。

---

### WINE_NEW_MEDIASOURCE
| 属性 | 值 |
|------|-----|
| 类型 | 复选框 |
| 默认 | `0` |
| 启用值 | `1` |

使用新版 Media Source 实现。启用 Wine 的视频/音频解码器，支持更多格式。

---

### WINE_LARGE_ADDRESS_AWARE
| 属性 | 值 |
|------|-----|
| 类型 | 复选框 |
| 默认 | `0` |
| 启用值 | `1` |

启用大地址感知（Large Address Aware）。允许 32 位 Windows 程序访问超过 2GB 的内存空间（最多 4GB），对某些内存需求大的游戏有帮助。

---

### WINEDLLOVERRIDES
| 属性 | 值 |
|------|-----|
| 类型 | 文本输入 |

Wine DLL 覆盖设置。用于指定哪些 DLL 使用 Wine 内置版本（builtin）或 Windows 原生版本（native），格式为 `dllname1,dllname2=n,b;...`。

示例：`msvcp140,concrt140=n,b` 表示使用原生版本，如果没有则回退到内置版本。

---

## 类型说明

| 类型 | 说明 |
|------|------|
| `CHECKBOX` | 复选框，切换 `0`/`1` 或 `false`/`true` |
| `SELECT` | 单选框，从预设选项中选择一个 |
| `SELECT_CUSTOM` | 可自定义的单选框 |
| `SELECT_MULTIPLE` | 多选框，可选择多个值（以逗号分隔） |
| `TEXT` | 自由文本输入 |
| `NUMBER` | 纯数字输入 |
| `DECIMAL` | 小数数字输入 |