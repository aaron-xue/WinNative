# Input Controls Profile 配置说明（.icp）

本文件描述 WinNative 输入控制（Input Controls）profile 的 `.icp` 配置格式。
`.icp` 文件是标准 JSON（不含注释）；下文示例使用 JSONC 形式（`//` 注释）仅用于说明。

## 文件位置与命名

| 类型 | 位置 | 说明 |
|---|---|---|
| 内置 | `assets/inputcontrols/profiles/controls-{id}.icp` | `id` 为 1、2、3、6、7、8；4、5 已退役 |
| 用户 | `{filesDir}/profiles/controls-{id}.icp` | `id` 从 `maxProfileId + 1` 递增，由程序分配 |

文件名为 `controls-{id}.icp`。内置 profile（`id <= 8`）为只读资产，用户在界面上的编辑修改的是
`{filesDir}/profiles/` 下的副本，可通过"重置"还原为初始快照。

## 顶层结构

```jsonc
{
  // 数字 | 必需 | Profile 唯一 ID。内置 1~8；自定义由程序分配，不可手工指定
  "id": 9,

  // 字符串 | 必需 | 显示名称。名称含 "template"（不区分大小写）的 profile 会被视为模板
  "name": "我的布局",

  // 数字 | 必需 | 鼠标光标速度倍率，默认 1.0
  "cursorSpeed": 1.0,

  // 数组 | 必需 | 屏幕控件元素列表（每个元素 = 一个可拖拽的按钮 / 摇杆 / 滑条等）
  "elements": [],

  // 数组 | 可选 | 物理手柄按键映射表。空数组不会写入文件；仅存在绑定过的手柄时出现
  "controllers": []
}
```

## element 结构（`elements[]` 中的每一项）

```jsonc
{
  // 字符串 | 必需 | 控件类型，见下表"控件类型"
  "type": "BUTTON",

  // 字符串 | 必需 | 形状：CIRCLE / RECT / ROUND_RECT / SQUARE
  "shape": "SQUARE",

  // 数字 | 可选 | ARGB 颜色值（如 0xFF3366CC = -13474484），-1 = 跟随默认主题色。
  // 保存时总会写出；读取时缺省则用默认色
  "customColor": -1,

  // 字符串数组 | 必需 | 绑定槽位列表，数组长度决定槽位数
  "bindings": ["KEY_W", "NONE", "NONE", "NONE"],

  // 数字 | 必需 | 缩放倍率，默认 1.0
  "scale": 1.0,

  // 数字 | 可选 | 透明度 0.0~1.0。仅在值 < 1.0 时写入文件；省略 = 1.0（不透明）
  "opacity": 0.85,

  // 数字 | 必需 | 横向相对坐标 0.0~1.0（相对屏幕宽），加载时乘屏幕宽度换算为像素
  "x": 0.1274,

  // 数字 | 必需 | 纵向相对坐标 0.0~1.0（相对屏幕高），加载时乘屏幕高度换算为像素
  "y": 0.3555,

  // 布尔 | 必需 | 切换开关模式：按下后保持按住状态，再按一次才松开
  "toggleSwitch": false,

  // 布尔 | 必需 | 是否可滑动触发（滑动方向 = 绑定的方向键），加载缺省视为 true
  "swipeable": true,

  // 字符串 | 必需 | 元素文字标签，iconId 为 0 时显示在控件上
  "text": "",

  // 数字 | 必需 | 图标索引 0~39（对应 assets/inputcontrols/icons/{id}.png），
  // 0 = 无图标、显示 text；34 为部分控件绘制时的默认回退图标
  "iconId": 0,

  // 字符串 | 仅 RANGE_BUTTON | 滑动区间：
  //   FROM_A_TO_Z(26) / FROM_0_TO_9(10) / FROM_F1_TO_F12(12) / FROM_NP0_TO_NP9(10)
  "range": "FROM_0_TO_9",

  // 数字 | 仅 RANGE_BUTTON | 滑动方向：0 = 水平，非 0 = 垂直。仅在非 0 时写入文件
  "orientation": 0
}
```

### 控件类型与绑定槽位

| `type` | 槽位数 | 槽位含义 |
|---|---|---|
| `BUTTON` | 4 | 槽 0 = 触发键（其余 NONE；也可多槽做组合键） |
| `D_PAD` | 4 | 槽 0~3 = 上 / 右 / 下 / 左（`GAMEPAD_DPAD_*` 或 `KEY_UP` 等） |
| `STICK` | 4 | 槽 0~3 = 上 / 右 / 下 / 左（`GAMEPAD_LEFT_THUMB_*` / `GAMEPAD_RIGHT_THUMB_*`） |
| `TRACKPAD` | 4 | 滑动映射鼠标移动 |
| `RANGE_BUTTON` | 6 | 滑动手势，需配合 `range` 字段 |
| `RADIAL_MENU` | ≥3 | 径向菜单子项，少于 3 个槽时加载会补齐到 3 |

## controller 结构（`controllers[]` 中的每一项）

```jsonc
{
  // 字符串 | 必需 | 手柄设备标识（设备 descriptor 或物理设备 ID）
  "id": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b",

  // 字符串 | 必需 | 手柄显示名称
  "name": "Xbox 360 Controller",

  // 数组 | 必需 | 键位映射列表；空列表的 controller 不会被写入文件
  "controllerBindings": [
    {
      // 数字 | 必需 | Android KeyEvent 键码（如 96 = DPAD_RIGHT）。
      // 负值表示摇杆轴（非真实键码）：
      //   -1=AXIS_X-  -2=AXIS_X+  -3=AXIS_Y-  -4=AXIS_Y+
      //   -5=AXIS_Z-  -6=AXIS_Z+  -7=AXIS_RZ- -8=AXIS_RZ+
      "keyCode": 96,

      // 字符串 | 必需 | 映射到的操作（Binding 枚举名，见下方列表）
      "binding": "MOUSE_LEFT_BUTTON",

      // 字符串 | 可选 | 绑定备注（仅用于界面展示与用户记忆，不参与映射）。
      // 仅在非空时写入文件；省略 = 无备注
      "note": "开镜"
    }
  ]
}
```

## Binding 枚举（`bindings` / `binding` 可用的全部值）

- **空**：`NONE`
- **鼠标**：`MOUSE_LEFT_BUTTON`、`MOUSE_MIDDLE_BUTTON`、`MOUSE_RIGHT_BUTTON`、
  `MOUSE_MOVE_LEFT`、`MOUSE_MOVE_RIGHT`、`MOUSE_MOVE_UP`、`MOUSE_MOVE_DOWN`、
  `MOUSE_SCROLL_UP`、`MOUSE_SCROLL_DOWN`
- **键盘**：`KEY_UP`、`KEY_RIGHT`、`KEY_DOWN`、`KEY_LEFT`、`KEY_ENTER`、`KEY_ESC`、
  `KEY_BKSP`、`KEY_DEL`、`KEY_TAB`、`KEY_SPACE`、`KEY_CTRL_L`、`KEY_CTRL_R`、
  `KEY_SHIFT_L`、`KEY_SHIFT_R`、`KEY_ALT_L`、`KEY_ALT_R`、`KEY_INSERT`、`KEY_HOME`、
  `KEY_END`、`KEY_PRTSCN`、`KEY_PG_UP`、`KEY_PG_DOWN`、`KEY_CAPS_LOCK`、`KEY_NUM_LOCK`、
  `KEY_MINUS`、`KEY_GRAVE`、`KEY_SEMICOLON`、`KEY_COMMA`、`KEY_PERIOD`、`KEY_SLASH`、
  `KEY_BACKSLASH`、`KEY_APOSTROPHE`、`KEY_BRACKET_LEFT`、`KEY_BRACKET_RIGHT`、
  `KEY_KP_ADD`、`F1`~`F12`、`0`~`9`、`A`~`Z`、`KP_0`~`KP_9`
- **手柄**：`GAMEPAD_BUTTON_A`、`GAMEPAD_BUTTON_B`、`GAMEPAD_BUTTON_X`、`GAMEPAD_BUTTON_Y`、
  `GAMEPAD_BUTTON_L1`、`GAMEPAD_BUTTON_R1`、`GAMEPAD_BUTTON_L2`、`GAMEPAD_BUTTON_R2`、
  `GAMEPAD_BUTTON_SELECT`、`GAMEPAD_BUTTON_START`、`GAMEPAD_BUTTON_L3`、`GAMEPAD_BUTTON_R3`、
  `GAMEPAD_LEFT_THUMB_UP`、`GAMEPAD_LEFT_THUMB_RIGHT`、`GAMEPAD_LEFT_THUMB_DOWN`、
  `GAMEPAD_LEFT_THUMB_LEFT`、`GAMEPAD_RIGHT_THUMB_UP`、`GAMEPAD_RIGHT_THUMB_RIGHT`、
  `GAMEPAD_RIGHT_THUMB_DOWN`、`GAMEPAD_RIGHT_THUMB_LEFT`、`GAMEPAD_DPAD_UP`、
  `GAMEPAD_DPAD_RIGHT`、`GAMEPAD_DPAD_DOWN`、`GAMEPAD_DPAD_LEFT`

## 读写规则要点

- `loadProfile` 只读取顶层 `id` / `name` / `cursorSpeed`；`elements` 与 `controllers` 按需懒加载。
  当某部分未在内存中加载/修改过时，`save()` 会原样沿用文件中的内容，避免误覆盖。
- `controllers` 仅在非空时写入；`customColor` 总是写出（默认 `-1`）；`opacity` 仅在 `< 1.0` 时写出；
  `range` / `orientation` 仅对 `RANGE_BUTTON` 写出；绑定项的 `note` 仅在非空时写出。
- `x` / `y` 存的是 0~1 相对坐标，读入时乘屏幕宽/高还原为像素，元素实际渲染位置由其中心决定。
- 内置 profile（`id <= 8`）不可被导入覆盖：导入同名 profile 时会新建一个可见的自定义 profile，
  避免静默覆盖只读资产。

## 相关代码位置

| 类 | 路径 |
|---|---|
| `ControlsProfile` | `app/src/main/runtime/input/controls/ControlsProfile.java` |
| `InputControlsManager` | `app/src/main/runtime/input/controls/InputControlsManager.java` |
| `ControlElement` | `app/src/main/runtime/input/controls/ControlElement.java` |
| `Binding` | `app/src/main/runtime/input/controls/Binding.java` |
| `ExternalController` | `app/src/main/runtime/input/controls/ExternalController.java` |
| `ExternalControllerBinding` | `app/src/main/runtime/input/controls/ExternalControllerBinding.java` |

