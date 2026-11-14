# 游戏 APK 修改清单（GAME-APK-MODIFICATIONS）

本仓库的 `gamepatch/` 源码与 `tools/` 脚本需要打进**游戏本体 APK** 才能工作。
本文档完整记录游戏 APK 的每一处修改——原理、位置、工具——供拿到新版本游戏
APK 的人重建，也供审计确认这个服务器 APK 到底改了游戏什么。

> 全部修改都基于本仓库自签的 APK（`offline.keystore`），原版商店 APK 无法直接套用。

## 总览

| # | 修改 | 位置 | 工具 |
|---|---|---|---|
| 1 | dex 注入 SaveFix / LanguageFix | `classes*.dex` | `gamepatch/src` + 自行编译 |
| 2 | libUE4.so 三处 ARM64 指令 patch | `lib/arm64-v8a/libUE4.so` | `tools/build_patched_so_apk.py` |
| 3 | Manifest 白名单 queriesPackages | `AndroidManifest.xml` | AXML 字符串池重建（见 §3） |
| 4 | pakchunk0 设置界面文字（简中） | `assets/main.obb.png` 内的 pak | `patch_pak0` 类脚本（见 §4） |
| 5 | versionName / versionCode | `AndroidManifest.xml` | 同 §3 的 AXML 编辑器 |

---

## 1. dex 注入：SaveFix / LanguageFix

游戏 `GameActivity.onCreate` 的第一行被替换为 `SaveFix.install()`，它按顺序做：

```
SaveFix.install()
  ├─ LanguageFix.apply()      从服务器 provider 拉语言包覆盖 TablePack/
  ├─ unpackObbDir()           /sdcard/Android/obb/<pkg>/ 下的 zip 解包到 files/
  ├─ unpackContent()          assets/content.zip 解包（韩文原版 TablePack 在此）
  ├─ restoreSave()            把固定的 account slot 写回 SaveGames/
  └─ startLogPump()           CM.log 转发（可选）
```

源码在 `gamepatch/src/com/closersrt/savefix/`。注入方式：编译这两个类成
classes.dex，与游戏原有 dex 合并（多 dex 或追加均可），并在 GameActivity 的
smali 里把 onCreate 开头替换成 `invoke-static SaveFix->install()`。

**关键坑**：`SaveFix.install()` 必须是 onCreate 的**第一条语句**——客户端解包
TableFiles.dat 的动作紧随其后，晚了就被先解包的韩文覆盖。

## 2. libUE4.so 三处 ARM64 指令 patch

`tools/build_patched_so_apk.py`（含完整说明与常量定义）。三处都是 Android 15
兼容性问题：

| 地址 | 原指令 | 改为 | 原因 |
|---|---|---|---|
| `0x07afe8a8` | `tbz w0,#0,skip` | `b skip` | Android 15 对 `GetMethodID(null)` 直接 abort（GamePot SDK 绑定时 FindClass 返回 null）。离线服不需要 GamePot，改成无条件跳到函数 epilogue |
| `0x07afe730` | `bl StorageCoreSDK::initSDK` | `mov w0,#0`（nop 掉调用） | 同类 abort，StorageCore 在 A15 同样死。返回值被调用者丢弃，安全 |
| `0x0be6aea8` | （X509_verify_cert 内部） | `mov w0,#1; ret` | 自签 patch 证书不被系统信任；游戏 libcurl 校验 HTTPS 证书时直接放行 |

⚠ **只 patch 这三处**。GamePot 本体保留（登录弹窗需要它的 UI 路径），把 GamePot
一起跳过会导致登录弹窗开了立刻关。

patch 后必须把 libUE4.so 以 **STORED** 方式放回 APK（原 APK 即如此），并重签。

## 3. AndroidManifest：queriesPackages 白名单

服务器 APK 的包名如果不是 `com.closersrt.proxy`，游戏的 Manifest 必须把该包名
加进 `<queries><package android:name="..."/>`，否则 Android 11+ 的包可见性过滤
会让游戏报 `No content provider: content://...lang/current`，语言包失效。

修改二进制 AXML 的要点（自写编辑器，约 100 行 Python）：

1. RES_STRING_POOL chunk 在文件头（type 0x0003）之后
2. 结构：28B header（type/hdrSize/chunkSize/stringCount/styleCount/flags/
   stringsStart/stylesStart）→ **offsets 表（4×count）** → 字符串数据（UTF-16LE，
   每串 u16 长度前缀 + NUL）
3. ⚠ offsets 表在 header 之后、stringsStart 之前——漏写它会让 round-trip 失败
4. 字符串变长时：重建整个 pool，重算 chunkSize 与 file header 的总 size；
   节点里的引用是 string index（相对），不需改
5. 改完用 `aapt2 dump badging` 验证（要放进完整 APK 才准，裸 AXML 会解析错）

同一次编辑顺带改 `versionName`（字符串池里的值）。

## 4. pakchunk0：设置界面文字简中

设置界面的文字**不在 TableFiles.dat 语言包里**，在 `assets/main.obb.png`
（STORED zip）内的 `CM/Content/Paks/pakchunk0-Android_ASTC.pak`。做法：

1. 从 pak 里定位目标韩文串的绝对 offset（逐个 probe 验证）
2. **等长 UTF-16LE 覆盖**（不足补全形空格 U+3000，超出截断）——pak 内所有
   index/offset 不变
3. 游戏的 ZipInputStream 不校验 CRC（原包 CRC 就是 0），重打 zip 安全
4. main.obb.png 保持 STORED 原样放回 APK

⚠ 从旧基线重建游戏 APK 时，**必须确认是否带上已 patch 的 obb**——只改版本号
这种小改动如果用了未 patch 的基线，设置界面汉化会无声消失（实测踩过）。

## 5. 重签

所有修改后统一 `zipalign -f -p 4` + `apksigner`（本仓库 `offline.keystore`，
密码 `pass:android` 或环境变量 `KS_PASS`）。同 key 覆盖安装不丢数据。

## 语言包（TableFiles.dat）构建

语言包生成器不在本仓库（依赖解包后的游戏表格与翻译数据）。格式已完全逆向：

- `TableFiles.dat`：多个 UTF-16LE JSON 段落串接（106 个 BOM），每表一段
- `TableFiles.map`：u16 长度前缀 UTF-16LE 行，格式 `file|name|ext|size`
- 修改任意表条目后必须**同步更新 map 中该条目的 size**，客户端按 map 切分容器

社区可自行构建翻译包，通过服务器 Web 界面上传（`/api/lang/importzip` 支持
任何 zh/ko 子集的 zip）。
