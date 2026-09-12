# FEX Core 环境变量说明

> 以下环境变量定义来自 `fexcore_env_vars.json`，用于控制 FEX（x86/x86_64 到 ARM64 模拟器）内核的运行时行为。

---

## 内存序 / TSO 设置

### FEX_TSOENABLED
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `0` |
| 可选值 | `0`, `1` |

启用完整的内存 TSO（Total Store Order，全序存储）模拟。x86 架构使用强内存序模型，而 ARM 使用弱内存序模型。启用此选项可以模拟 x86 的 TSO 行为。
- `0`：不启用 TSO 模拟（默认）
- `1`：启用 TSO 模拟（提高兼容性，但降低性能）

---

### FEX_VECTORTSOENABLED
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `0` |
| 可选值 | `0`, `1` |

启用向量（Vector）指令的 TSO 内存序模拟。当只启用 `FEX_TSOENABLED` 时，向量操作可能仍使用弱内存序。启用此选项可对向量指令也施加 TSO 约束。
- `0`：向量指令使用弱内存序（默认）
- `1`：向量指令使用 TSO 内存序

---

### FEX_HALFBARRIERTSOENABLED
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `0` |
| 可选值 | `0`, `1` |

启用半屏障（Half-Barrier）TSO 模拟。一种折中方案，在 TSO 兼容性与性能之间取得平衡。
- `0`：不使用半屏障 TSO（默认）
- `1`：使用半屏障 TSO 模拟

---

### FEX_MEMCPYSETTSOENABLED
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `0` |
| 可选值 | `0`, `1` |

对 `memcpy`/`memset` 等内存操作函数施加 TSO 内存序约束。这些函数在 x86 上具有隐式的内存序保证。
- `0`：不对 memcpy/memset 施加 TSO（默认）
- `1`：对 memcpy/memset 施加 TSO 内存序

---

### FEX_STRICTINPROCESSSPLITLOCKS
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `0` |
| 可选值 | `0`, `1` |

对进程内的分离锁（Split Locks）进行严格检查。分离锁指跨越缓存行边界的原子操作，在 x86 上允许但 ARM 上不支持。
- `0`：不进行严格检查（默认）
- `1`：对进程内分离锁进行严格检查

---

## CPU 性能与代码生成

### FEX_KERNELUNALIGNEDATOMICBACKPATCHING
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `1` |
| 可选值 | `0`, `1` |

启用内核级非对齐原子操作的反向修补（Backpatching）。当 ARM 硬件不支持非对齐原子操作时，通过反向修补来模拟 x86 的非对齐原子行为。
- `0`：禁用反向修补
- `1`：启用反向修补（默认，提高兼容性）

---

### FEX_X87REDUCEDPRECISION
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `1` |
| 可选值 | `0`, `1` |

启用 x87 FPU 的降低精度模式。x87 使用 80 位扩展精度，而 ARM 仅支持 64 位双精度。启用此选项可加速 x87 指令模拟。
- `0`：使用完整精度模拟（较慢）
- `1`：使用降低精度模拟（默认，推荐）

---

### FEX_MULTIBLOCK
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `1` |
| 可选值 | `0`, `1` |

启用多代码块（Multiblock）编译。将多个基本块合并为一个更大的编译单元，以便进行更积极的优化。
- `0`：单块编译（较慢）
- `1`：多块编译（默认，提高性能）

---

### FEX_MAXINST
| 属性 | 值 |
|------|-----|
| 类型 | 数字 |
| 默认值 | `5000` |
| 可选值 | `5000`（可修改） |

设置每个代码块的最大指令数。超过此数量的指令将结束当前代码块。默认值 `5000` 适用于大多数场景。
- 增大此值：可能提高运行时性能，但会增加编译开销
- 减小此值：编译更快，但运行时性能可能下降

---

### FEX_HOSTFEATURES
| 属性 | 值 |
|------|-----|
| 类型 | 选择 |
| 默认值 | `off` |
| 可选值 | `off`, `enablesve`, `disablesve`, `enableavx`, `disableavx`, `enableafp`, `disableafp`, `enablelrcpc`, `disablelrcpc`, `enablelrcpc2`, `disablelrcpc2`, `enablecssc`, `disablecssc`, `enablepmull128`, `disablepmull128`, `enablerng`, `disablerng`, `enableclzero`, `disableclzero`, `enableatomics`, `disableatomics`, `enablefcma`, `disablefcma`, `enableflagm`, `disableflagm`, `enableflagm2`, `disableflagm2`, `enablefrintts`, `disablefrintts`, `enablecrypto`, `disablecrypto`, `enablerpres`, `disablerpres`, `enablesvebitperm`, `disablesvebitperm`, `enablepreserveallabi`, `disablepreserveallabi`, `enablewfxt`, `disablewfxt`, `enable3dnow`, `disable3dnow`, `enablesse4a`, `disablesse4a`, `enablemops`, `disablemops` |

控制主机（Host）CPU 特性的启用/禁用。通过此变量可以精确控制哪些 ARM CPU 扩展特性暴露给 FEX 模拟器。

| 选项 | 说明 |
|------|------|
| `off` | 使用默认设置（不修改任何特性） |
| `enablesve` / `disablesve` | 启用/禁用 SVE（可伸缩向量扩展） |
| `enableavx` / `disableavx` | 启用/禁用 AVX 指令模拟支持 |
| `enableafp` / `disableafp` | 启用/禁用 AFP（替代浮点） |
| `enablelrcpc` / `disablelrcpc` | 启用/禁用 LRCPC（释放一致性处理器一致） |
| `enablelrcpc2` / `disablelrcpc2` | 启用/禁用 LRCPC2 |
| `enablecssc` / `disablecssc` | 启用/禁用 CSSC |
| `enablepmull128` / `disablepmull128` | 启用/禁用 PMULL 128 位乘法 |
| `enablerng` / `disablerng` | 启用/禁用 RNG（随机数生成器） |
| `enableclzero` / `disableclzero` | 启用/禁用 CLZERO（缓存行清零） |
| `enableatomics` / `disableatomics` | 启用/禁用原子操作扩展 |
| `enablefcma` / `disablefcma` | 启用/禁用 FCMA（浮点复数乘加） |
| `enableflagm` / `disableflagm` | 启用/禁用 FlagM（标志操作） |
| `enableflagm2` / `disableflagm2` | 启用/禁用 FlagM2 |
| `enablefrintts` / `disablefrintts` | 启用/禁用 FRINTTS（浮点取整到有符号整数） |
| `enablecrypto` / `disablecrypto` | 启用/禁用加密扩展 |
| `enablerpres` / `disablerpres` | 启用/禁用 RPRES（指令预取保留） |
| `enablesvebitperm` / `disablesvebitperm` | 启用/禁用 SVE 位排列 |
| `enablepreserveallabi` / `disablepreserveallabi` | 启用/禁用 PreserveAll ABI |
| `enablewfxt` / `disablewfxt` | 启用/禁用 WFXT |
| `enable3dnow` / `disable3dnow` | 启用/禁用 3DNow! 指令模拟 |
| `enablesse4a` / `disablesse4a` | 启用/禁用 SSE4a 指令模拟 |
| `enablemops` / `disablemops` | 启用/禁用 MOPS（内存操作） |

---

## 兼容性与时间

### FEX_SMALLTSCSCALE
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `1` |
| 可选值 | `0`, `1` |

使用较小的 TSC（时间戳计数器）缩放因子。影响模拟环境中时间相关指令的精度和性能。
- `0`：使用完整缩放
- `1`：使用较小缩放（默认）

---

### FEX_HIDEHYBRID
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `1` |
| 可选值 | `0`, `1` |

隐藏宿主 CPU 的异构（hybrid）架构信息。某些应用程序会在检测到 hybrid CPU（如 ARM big.LITTLE）时改变行为。
- `0`：暴露真实的 CPU 拓扑（可能引起兼容性问题）
- `1`：隐藏异构 CPU 信息（默认，推荐）

---

### FEX_SMCCHECKS
| 属性 | 值 |
|------|-----|
| 类型 | 选择 |
| 默认值 | `mtrack` |
| 可选值 | `none`, `mtrack`, `full` |

控制自修改代码（Self-Modifying Code, SMC）的检测机制。

| 选项 | 说明 |
|------|------|
| `none` | 不检测 SMC（最快，但使用 SMC 的程序会出错） |
| `mtrack` | 使用内存跟踪检测（默认，平衡性能与兼容性） |
| `full` | 全面检测（最安全，但性能开销最大） |

---

### FEX_MONOHACKS
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `1` |
| 可选值 | `0`, `1` |

启用 .NET/Mono 运行时相关的兼容性补丁。某些使用 Mono 框架的游戏（如 Unity 游戏）需要此选项才能正常运行。
- `0`：不启用 Mono 兼容性补丁
- `1`：启用 Mono 兼容性补丁（默认）

---

### FEX_HIDEHYPERVISORBIT
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `0` |
| 可选值 | `0`, `1` |

隐藏 CPU 的虚拟机监控程序位（Hypervisor Bit）。某些反作弊/反篡改软件会检测此位来判断是否在虚拟化环境中运行。
- `0`：不隐藏（默认）
- `1`：隐藏虚拟机监控程序位（可用于过反作弊检测）

---

## 代码缓存与元数据

### FEX_VOLATILEMETADATA
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `1` |
| 可选值 | `0`, `1` |

启用代码缓存中元数据的易失性（Volatile）处理。控制编译后的代码块元数据是否可以在内存压力下被丢弃。
- `0`：元数据持久保存
- `1`：元数据可被丢弃（默认，节省内存）

---

## CPU 缓存配置

### FEX_DISABLEL2CACHE
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `0` |
| 可选值 | `0`, `1` |

禁用 FEX 内部的 L2 模拟代码缓存。代码缓存用于存储编译后的代码块，提高重复执行效率。
- `0`：启用 L2 代码缓存（默认）
- `1`：禁用 L2 代码缓存（节省内存，降低性能）

---

### FEX_DYNAMICL1CACHE
| 属性 | 值 |
|------|-----|
| 类型 | 开关 |
| 默认值 | `0` |
| 可选值 | `0`, `1` |

启用动态 L1 代码缓存。根据运行时的命中率自动调整代码缓存大小。
- `0`：使用固定大小的 L1 缓存（默认）
- `1`：动态调整 L1 缓存大小

---

### FEX_DYNAMICL1CACHEINCREASECOUNTHEURISTIC
| 属性 | 值 |
|------|-----|
| 类型 | 数字 |
| 默认值 | `250` |
| 可选值 | `250`（可修改） |

动态 L1 缓存增大触发阈值。当代码块的命中次数达到此值时，扩大 L1 缓存。仅在 `FEX_DYNAMICL1CACHE=1` 时生效。
- 增大此值：缓存增长更保守，更节省内存
- 减小此值：缓存增长更激进，可能提高性能

---

### FEX_DYNAMICL1CACHEDECREASECOUNTHEURISTIC
| 属性 | 值 |
|------|-----|
| 类型 | 数字 |
| 默认值 | `50` |
| 可选值 | `50`（可修改） |

动态 L1 缓存缩小触发阈值。当缓存命中率低于此值时，缩小 L1 缓存。仅在 `FEX_DYNAMICL1CACHE=1` 时生效。
- 增大此值：缓存更频繁缩小，更节省内存
- 减小此值：缓存更持久，可能提高性能