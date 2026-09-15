# 翻译守卫使用说明（只保留英文 + 简体中文）

本仓库（WinNative 分支）只发布 **英文 `app/src/main/res/values/`** 和
**简体中文 `app/src/main/res/values-zh-rCN/`**。上游 `main` 会不断新增/修改其它语言的
`strings.xml`，而只要上游改过、我们这边已经删除，Git 就会报
`CONFLICT (modify/delete)` 并把**上游那一份写回工作区**——这就是之前每次合并都被塞回来的原因。

本目录下的钩子就是为了防止这件事。

---

## 一、已经做了什么（无需重复操作）

以下都已在本机完成并提交进仓库，克隆后会自动带上：

1. 删除了 21 个语言目录：
   `values-b+es+419`、`values-da`、`values-de`、`values-es`、`values-fi`、`values-fr`、
   `values-hi`、`values-it`、`values-ja`、`values-ko`、`values-no`、`values-pl`、
   `values-pt`、`values-pt-rBR`、`values-ro`、`values-ru`、`values-sv`、`values-th`、
   `values-tr`、`values-uk`、`values-zh-rTW`
2. 保留目录（**不要删**）：`values`（英文）、`values-zh-rCN`（简体中文）、
   `values-v27`（这是 Android API 级别限定符，不是语言）。
3. 新增钩子与脚本（见下「文件清单」），并已执行
   `git config core.hooksPath tools/git/hooks`（写入本机 `.git/config`）。

> 源码侧本来就只声明了英文和简体中文，无需改动：
> `app/build.gradle` 的 `resConfigs "en", "zh-rCN"`、
> `app/src/main/res/xml/locales_config.xml`、
> `app/src/main/shared/android/LocaleHelper.kt` 的 `SUPPORTED_LANGUAGE_TAGS`。

---

## 二、新克隆一份仓库时：只做一次

```sh
sh tools/git/setup-hooks.sh
```

等价命令（手动版）：

```sh
git config core.hooksPath tools/git/hooks
```

脚本会顺手清掉一次残留的多余语言目录，并打印确认信息。

> `core.hooksPath` 是**本地配置**，不会随克隆传递，所以每台机器/每个克隆都要跑一次。
> 旧目录 `.git/hooks` 在切换后不再生效，其中原来 4 个 Git LFS 钩子（post-checkout /
> post-commit / post-merge / pre-push）已逐字复制到 `tools/git/hooks/`，LFS 功能不受影响。

---

## 三、日常合并上游：操作步骤

### 正常情况（推荐流程）

```sh
git fetch origin
git merge origin/main          # 或你用的上游远端名字
```

- `post-merge` 钩子会自动清掉被带回来的 `values-*` 目录，**结束后 `git status` 应保持干净**。
- 如果没有任何多余语言，钩子不会输出任何东西，静默通过。

### 出现冲突时（关键）

如果看到这种提示：

```
CONFLICT (modify/delete): app/src/main/res/values-xx/strings.xml deleted in HEAD
                          and modified in <上游>. Version <上游> left in tree.
Automatic merge failed; fix conflicts and then commit the result.
```

**冲突中断的合并不会触发 `post-merge` 钩子**，所以按下面两步走：

```sh
sh tools/git/purge-translations.sh   # ① 清理（同时解决这些冲突）
git status                           # ② 确认没有 values-xx 残留后提交
git commit
```

### 万一忘了清理

`pre-commit` 钩子是最后一道保险：**任何提交都不可能带上被删语言**，无论它是通过
merge、cherry-pick、`git checkout 上游 -- path` 还是 `git add -A` 进来的。
所以正常情况下这些语言永远进不了提交历史。

---

## 四、文件清单

| 文件 | 作用 |
| --- | --- |
| `purge-translations.sh` | 核心清理：把 `app/src/main/res/values-*` 中除 `values` / `values-v27` / `values-zh-rCN` 之外的目录，从**索引和工作区一起**删掉。可随时手动执行。 |
| `hooks/post-merge` | Git LFS 原钩子 + 清理 → `git merge` / `git pull` 之后立即生效。 |
| `hooks/pre-commit` | 清理 → 兜底保证提交里没有多余语言。 |
| `hooks/post-checkout`、`hooks/post-commit`、`hooks/pre-push` | Git LFS 原钩子的逐字副本，保证切换 `core.hooksPath` 后 LFS 不失效。 |
| `setup-hooks.sh` | 一次性安装脚本。 |
| `README.md` | 本文档。 |

---

## 五、为什么不用 `.gitattributes`

看起来最自然的做法是给这些路径加 `merge=ours` 之类的自定义合并驱动，但实测（用
`git merge-tree --write-tree` 对本仓库真实提交做过验证）：

- `merge=binary`：仍然 `CONFLICT (modify/delete)`，上游文件被写回；
- 自定义 `merge=keepours`（`driver = true`）：结果同上，完全一致。

原因：**Git 对「一方删除 / 另一方修改」的冲突不会调用 merge driver**，只在内容三方合并时才调用。
所以在 `.gitattributes` 里加属性对这个场景无效，只能放在钩子里。

---

## 六、常见问题

**Q：合并时弹 `index.lock` 重命名失败的对话框（TortoiseGit “Git CLI yes/no wrapper”）怎么办？**

A：那是别的文件（杀毒、搜索索引服务，或另一个 git 进程）占用了 `.git/index.lock`。
点「是」重试即可；持续失败就先关掉其它 git / TortoiseGit 窗口，或把仓库目录（尤其 `.git`）
加入 Windows Defender 排除列表。它只会让那一次写索引失败，**不会造成半写状态**，
合并后用 `git status` 确认即可。

**Q：以后想恢复某一种语言怎么办？**

A：四步都要做，缺一不可：

1. 在 `purge-translations.sh` 的 `keep="..."` 白名单里加上该目录名，否则会被钩子删掉；
2. `app/build.gradle` 的 `resConfigs` 加上对应语言；
3. `app/src/main/res/xml/locales_config.xml` 增加 `<locale android:name="..."/>`；
4. `app/src/main/shared/android/LocaleHelper.kt` 的 `SUPPORTED_LANGUAGE_TAGS`
   与 `NATIVE_LANGUAGE_NAMES` 同步添加。

**Q：钩子能跳过吗？**

A：能（`--no-verify`），但请不要对翻译目录使用；跳过就等于放弃这道保护。
