# ClosersRT Offline Server

ClosersRT（클로저스RT）Android 客户端的**本地离线服务器**：一个 App 同时提供
web / game / patch 三个服务（127.0.0.1:8080 / 8500 / 8443），让游戏在没有官方
服务器的情况下运行，并提供存档管理、资产编辑、简体中文汉化等能力。

> 原作者：**cs10108**
> 本项目仅供学习研究，游戏资源版权归 NADDIC Games 所有。

目前做到這個版本暫時不會更新了 有其他功能需求可以自己找AI做
遊戲資源請到某盤下載
/s/1FTDeEgg6vzq7KQJVDS6t4g?pwd=csnb

## 组成

| 目录 | 内容 |
|---|---|
| `proxy_app/` | 服务器 App（Android，包名 `com.closersrt.server`） |
| `gamepatch/src/` | 游戏端注入源码：`SaveFix`（内容解包/存档恢复）与 `LanguageFix`（语言包套用） |
| `tools/` | 构建脚本（APK 打包/签名/obb 处理） |
| `docs/INSTALL-USER.md` | 用户安装与汉化指南 |

## 功能

- 本机三服务：web(8080) / game(8500) / patch(8443)，游戏全程离线
- 内容包 zip 选择与自动解包（PutCache/PakCache/Level/Scenario/Table…）
- **存档管理**：Web 界面导出 / 导入 / 重设基准预设
- **资产编辑**：金币稜镜、道具（240 装备 + 112 消耗品默认入库）、角色卡、等级/技能
- **好感度**：27 个叙事角色默认 9 级，互动/送礼/外出正常累积升级
- **语言包**：一次上传含简中+韩文的 bundle zip，之后自由切换（韩文为游戏原版）
- 新手教学跳过开关、宿舍格子、服装、GM 悬浮球
- Android 11~15 兼容（MuMu Player 12 实测）

## 构建

```bash
# 0. 生成 patch server 自签证书（只需一次；产物进 proxy_app/assets/）
python tools/make_patch_cert.py

# 1. 服务器 App（需要 Android SDK build-tools 34 + JDK 21）
python tools/build_proxy_apk.py --out build/Closers-RTServer.apk
```

注意：仓库不含 `patch.key` / `patch.p12`（TLS 私钥），用上面的
`make_patch_cert.py` 重新生成即可，生成的证书与游戏端信任的 patch CA
无需一致——首次安装时客户端会走代理下发的信任链。

游戏本体的构建（patched libUE4 / SaveFix 注入）不在本仓库范围内——需要原版
游戏 APK 与逆向产物。仓库提供 `tools/build_obb.py`（内容包转 obb）与
`tools/build_final3_apk.py`（重签）作为参考。

## 编译期开关（BuildFlags.java）

| flag | 默认 | 说明 |
|---|---|---|
| `AUTO_START_SERVERS` | false | 打开 App 是否自动启动三服务（现在是手动按 START SERVER） |
| `AUTO_START_PROXY` | false | 是否自动启动 VPN 隧道（离线版不需要） |

## 安装（用户）

见 [docs/INSTALL-USER.md](docs/INSTALL-USER.md)。

## 目录速览

```
proxy_app/src/com/closersrt/server/   服务器 Java 源码（29 个类）
  GameServer.java      游戏协议核心（登录/角色/物品/宿舍/好感）
  LocalWebServer.java  Web 管理界面 + HTTP API
  SaveStore.java       存档（角色成长/道具/资金/剧情进度）
  AssetStore.java      基准资产（新帐号默认值）
  Language.java        语言包暂存与切换
gamepatch/src/.../savefix/            游戏端注入
  SaveFix.java         content.zip 解包、存档槽恢复
  LanguageFix.java     语言包套用（content provider 拉取）
tools/                                构建脚本
```

## License

本項目以 [**CC BY-NC-SA 4.0**](LICENSE)（Attribution-NonCommercial-ShareAlike
4.0 International）授權發布：

- **BY 署名** — 必須標示原作者（cs10108）
- **NC 非商用** — 不得將本項目或其修改版用於商業目的、不得售賣
- **SA 相同方式共享** — 修改後的版本必須以相同授權開源

ClosersRT 及所有遊戲素材版權歸 NADDIC Games 所有，本倉庫不含任何遊戲資源，
僅供學習研究使用。
