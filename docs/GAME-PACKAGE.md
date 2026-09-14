# `.game` 游戏包格式说明

`.game` 是「添加自定义游戏」支持的**导入包格式**：它本质上是一个**压缩包改名**成 `.game` 后缀，
里面放一份 `manifests.json`（以及可选的封面图）。用户在库界面选中 `.game` 文件后，WinNative 会
解析清单、自动填好游戏名/可执行文件/封面，用户确认即可加入库。

相关代码：

| 位置 | 作用 |
| --- | --- |
| `app/src/main/app/shell/UnifiedActivityDrawer.kt` → `AddCustomGameDialog` / `selectExecutable()` | 选择文件、识别 `.game`、回填表单 |
| `app/src/main/feature/shortcuts/GamePackageImporter.kt` | 解包 + 解析 `manifests.json` |
| `app/src/main/shared/io/ArchiveExtractor.kt` | 底层解压（zip/7z/tar/gz/bz2/xz/zstd） |
| `app/src/main/app/shell/UnifiedActivityDrawer.kt` → `addCustomGame()` / `RetroShortcuts.create()` | 真正创建快捷方式 |
| `app/src/main/shared/android/DirectoryPickerDialog.kt` | 文件选择器的可执行后缀白名单（含 `game`） |

---

## 1. 使用方式（普通用户）

1. 把 `.game` 文件放到设备上（例如 `Download` 或内部存储根目录）。
   > Android 11+ 浏览 `Download` 等目录需要「所有文件访问权限」，缺失时应用会弹提示并跳转授权页。
2. 打开库界面 → **添加自定义游戏（Add Custom Game）** → 点击 **Select Executable or Console ROM**。
3. 选中 `.game` 文件。解析成功后：
   - 游戏名 = `manifest.name`（可在对话框里修改）；
   - 可执行文件 = `manifest.exe` 解析出的真实路径；
   - 封面 = `manifest.cover` 复制出的封面文件（若清单里没写封面，则尝试从 exe 里提取图标）。
4. 确认 **Add** 即可入库。

---

## 2. 包结构

`.game` 文件本身是一个压缩包，**压缩包根目录必须直接包含 `manifests.json`**（不要再套一层文件夹）。

```
MyGame.game            ← 实际上是 zip / 7z / tar(.gz) 等改名而来
├── manifests.json     ← 必需，必须在根目录
└── cover.png          ← 可选，任意文件名/路径，由 manifest.cover 指定
```

支持的解压格式（按文件头魔数识别，与文件名后缀无关）：

- ZIP、7z
- TAR、TAR.GZ / TGZ、TAR.BZ2、TAR.XZ、TAR.ZST
- 单独的 GZ / BZ2 / XZ / ZST 流

因此最简单的做法就是：**打包成 zip 后改名为 `.game`**。

---

## 3. `manifests.json` 字段

JSON 键名**大小写敏感**，必须是小写。

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `name` | 是 | string | 游戏显示名。空串/缺失会报错。作为对话框里的默认名称，用户可修改。 |
| `exe` | 是 | string | 要启动的**可执行文件或主机 ROM** 的路径，见第 4 节路径规则。指向的文件必须真实存在。 |
| `cover` | 否 | string | 封面图路径，相对压缩包根目录（也可写绝对路径）。文件存在时会被复制到应用私有封面目录。 |

示例：

```json
{
  "name": "Half-Life",
  "exe": "/storage/emulated/0/Games/Half-Life/hl.exe",
  "cover": "cover.png"
}
```

主机（复古）游戏示例——`exe` 直接指向 ROM：

```json
{
  "name": "Super Mario World",
  "exe": "/storage/emulated/0/ROMs/snes/Super Mario World.sfc",
  "cover": "art/smw.jpg"
}
```

### 打包示例

```bash
# Windows PowerShell
Compress-Archive -Path .\manifests.json, .\cover.png -DestinationPath MyGame.zip
Rename-Item MyGame.zip MyGame.game
```

> `ArchiveExtractor` 用压缩包内的相对条目名解压到临时目录，因此条目名不能是绝对路径、不能含 `..`，
> 这类条目会被直接跳过。

---

## 4. 路径解析规则（最容易踩坑的地方）

### `exe`

- 以 `/` 开头 → 当作**设备上的绝对路径**直接使用。
- 不以 `/` 开头 → 相对**`.game` 文件所在的目录**解析，而不是压缩包内部！

```kotlin
val baseDir = gameFile.parentFile!!          // .game 文件所在目录
val exeFile = resolvePath(baseDir, exeRaw)   // raw.startsWith("/") ? File(raw) : File(root, raw)
```

因为导入时的临时解包目录在解析完就被删除，**压缩包内自带的游戏文件不会留在设备上**，所以：

- 推荐做法：`exe` 写**绝对路径**，指向设备上已存在的游戏；
- 或者把 `.game` 和解包后的游戏目录放在同一层，用相对路径（如 `"MyGame/game.exe"`）。

### `cover`

- 不以 `/` 开头 → 相对**解包临时目录（即压缩包根目录）**解析，所以封面图必须打**进包里**；
- 以 `/` 开头 → 直接读设备上的该文件；
- 复制目标为 `<应用私有目录>/artwork/<随机 UUID>/cover.<原扩展名>`，扩展名为空时补 `.png`；
- 复制失败或文件不存在时不报错，仅表现为“无封面”。

### 加入库时的 Windows 路径转换

真正创建快捷方式时会调用 `WineUtils.resolveGameExeWindowsPath(container, "CUSTOM", gameFolder, exePath)`：
优先把游戏目录映射为容器 C: 下的符号链接（形如 `C:\WinNative\Games\CUSTOM\<link>\...`），
否则尝试用容器现有的盘符映射；最终生成启动命令 `wine "<windowsPath>"`。

---

## 5. 导入流程（内部实现）

`AddCustomGameDialog.selectExecutable(path)`：

1. 后缀（忽略大小写）为 `game` → 走导入包分支（否则按普通 exe/ROM 处理）。
2. 在 `.game` 同级目录创建临时目录 `.wn-import-<UUID>`，解压进去。
3. 读取 `<临时目录>/manifests.json` 并解析 `name` / `exe` / `cover`。
4. 解析 `exe`（见上），校验文件存在；解析并复制 `cover`。
5. 用解析结果回填对话框；清空临时目录（`finally` 中递归删除）。
6. 用户点 **Add**：
   - 若 `exe` 后缀能识别为主机 ROM（`RetroSystems.detectForFile`）→ 走 `RetroShortcuts.create()`，
     生成复古快捷方式，游戏目录 = ROM 所在目录；
   - 否则走 `addCustomGame()`，生成 `.desktop` 快捷方式，游戏目录由
     `LibraryShortcutUtils.detectCustomGameFolder(exe)` 推断（也可在对话框里手动改）。

**Add 可用条件**：已选可执行文件 + 名称非空 + 未在提交中 + （识别到主机系统 **或** 已得到游戏目录）。

---

## 6. 错误提示对照

导入阶段的异常信息（`ImportException`）会通过 Toast 原样展示：

| 提示 | 原因 |
| --- | --- |
| `文件不存在: <path>` | 选中的 `.game` 不是文件 |
| `不支持的文件格式: <name>` | 后缀不是 `game` |
| `文件解包失败: <msg>` | 压缩格式无法识别/损坏 |
| `安装包缺少 manifests.json` | 根目录没有 `manifests.json`（很可能是套了一层目录） |
| `manifests.json 解析失败: <msg>` | 不是合法 JSON（如多了尾随逗号、注释） |
| `manifest.name 不能为空` | 缺少 `name` 或为空串 |
| `manifest.exe 不能为空` | 缺少 `exe` 或为空串 |
| `manifest.exe 路径不存在: <raw> → <resolved>` | 解析出的路径在设备上找不到文件 |

---

## 7. 注意事项与限制

- `.game` 只承载**元数据**：解包目录是临时的，不会被搬进游戏库；包里的游戏本体不会被安装。
- 因此 `exe` 通常应指向设备上**已经存在**的游戏；否则请把游戏目录放在 `.game` 旁边并使用相对路径。
- `manifests.json` 必须位于压缩包**根目录**，键名必须严格是 `name` / `exe` / `cover`。
- `exe` 建议是 Windows 可启动文件（`.exe` `.bat` `.cmd` `.msi`）或受支持的主机 ROM
  （支持列表见 [RETRO-CONSOLES.md](RETRO-CONSOLES.md)）；指向其它类型文件不会报错，但无法正常启动。
- 封面优先级：`manifest.cover` > 从 exe 提取的图标 > 自动刮削（需开启自动刮削，且仅 PC 游戏路径）。
- 删除游戏时，`removeCustomGame()` 依据快捷方式的 `custom_game_folder` 匹配并清理资源，
  不会删除 `.game` 文件本身。
