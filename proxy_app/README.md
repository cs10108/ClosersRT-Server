# Closers RT 本地代理 APK

> **設計原則：完全不去改遊戲 APK。**
> 遊戲用原版安裝，所有離線化工作都由這個獨立的小 APK 完成。

---

## 為什麼這個架構比改 APK 好

| 改遊戲 APK（舊路線） | 本地代理 APK（這條路） |
|---|---|
| 重打包 788 MB + 重簽 | 代理 APK 只有 **29 KB** |
| 每個 bug 都要重建 788 MB | 改一行 Java，2 秒重建 |
| 要處理簽章、反外掛、OBB 位置、Frida 相容性 | **這些問題全部不存在** |
| 遊戲更新就全部重來 | 遊戲換版本照樣能用 |

上一輪在模擬器上抓到的兩個 bug（OBB 必須在 APK 內、Frida 設定檔必須叫
`libfrida-gadget.config.so`）正是舊路線的代價。這條路線沒有這些問題。

---

## 運作原理

遊戲會連兩個地方：

1. `https://download.closersrt.com/Patch_Live/...`（HTTPS，libcurl）
2. 從 `service-live.txt` 讀到的遊戲伺服器 IP，連接埠 **8500**（純 TCP）

在**未 root** 的手機上，唯一能合法重導其他 App 流量的機制是 **`VpnService`**。
所以代理建立一條 tun，把全部流量導進來，然後在使用者空間自行處理：

```
遊戲 → tun → TunRelay
                 ├─ UDP/53  → Dns.java      回一個固定假 IP（10.9.9.8）
                 └─ TCP     → 依目的埠分流
                                8500 → GameServer（CM 協定）
                                其他 → 記錄 + RST
```

代理自己開的 socket 全部經過 `VpnService.protect()`，所以**不影響手機正常上網**。

---

## 使用者流程（照你描述的）

| 步驟 | 動作 | 對應實作 |
|---|---|---|
| 1 | 複製所有檔案進手機 | 任意 |
| 2 | 安裝本地代理 | `build/closers-proxy.apk` |
| 3 | 安裝本體 | **原版 APK，不動** |
| 4 | 代理裡匯入資料包 | 後台「匯入資料包」→ `POST /api/import?name=...` |
| 5 | 建立帳號、載入 JSON | 後台「帳號」→ `POST /api/user/create`、`/api/user/activate` |
| 6 | Start 開玩 | App 上的「啟動代理」→ 授權 VPN → 開遊戲 |

**網頁後台：`http://127.0.0.1:8080/`**（App 上有按鈕可直接開啟）

---

## 後台 API

| 路徑 | 方法 | 說明 |
|---|---|---|
| `/` | GET | 儀表板（狀態、匯入、帳號、日誌） |
| `/api/status` | GET | JSON 狀態 |
| `/api/log` | GET | 最近 150 行日誌 |
| `/api/profiles` | GET | 已匯入的資料包 |
| `/api/import?name=X` | POST | 匯入資料包（body = JSON 或純文字） |
| `/api/users` | GET | 帳號清單 |
| `/api/user/create?name=X` | POST | 建立帳號 |
| `/api/user/activate?name=X` | POST | 切換使用中的帳號 |

實測輸出：

```
/api/status  200  {"vpn_running":false,"web_running":true,
                   "game_server":"127.0.0.1:8500","flows":0,
                   "packets_in":0,"packets_out":0,
                   "users":[{"name":"player","accountId":1000,"active":true}]}
create alice -> {"ok":true,"accountId":1001}
import       -> {"ok":true,"name":"chapters"}
```

---

## 遊戲協定（Java 實作）

封包框架是從 `libUE4.so` 反組譯還原的（詳見 `server/PROTOCOL.md`）：

```
+0x00 u32 size (= 24 + payload)
+0x04 u32 opcode
+0x08 u64 session
+0x10 u32 count
+0x14 u32 flags = 0x1000
+0x18 payload
```

第 14/15 章：`SC_CHAPTERINFORES(324)` 的記錄格式也已解出 ——
`u32 count` + 每筆 `byte[0x1c]` 固定區 + `u16 長度` + 資料。
`GameServer.chapterPayload()` 會把清單補上第 14、15 章。

---

## 建置（不需要 Gradle）

```powershell
python tools\build_proxy_apk.py
```

流程：`javac` → `d8` → `aapt2 link` → `zipalign` → `apksigner`
（全部用已安裝的 SDK build-tools，沒有 Gradle、沒有 Android Studio、沒有 NDK）

---

## 踩到的坑（都已修，供後人參考）

### 1. `d8` 對**非靜態內部類別**會內部崩潰

```
java.lang.NullPointerException: Cannot invoke "String.length()"
    because "<parameter1>" is null
```

- **非靜態**內部類別（`Outer$Inner`，捕獲外層實例）→ d8 崩潰
- **靜態**巢狀類別（`static class`）→ 正常
- 匿名類別（`Outer$1`）→ **也會崩潰**
- 合成 `Outer$1`（javac 為私有建構子產生）→ 正常

build-tools 33.0.2 與 34.0.0 的 d8 **都**有這個行為，所以不是版本問題。

**結論：這個專案所有巢狀類別都寫成 `static`，且不使用匿名類別**，
外層實例以建構子參數明確傳入。

### 2. `d8` 不會自己從目錄拉入巢狀類別

只傳外層類別會讓 APK 建置成功但執行期爆
`NoClassDefFoundError: Foo$Bar`。必須明確列出所有類別檔。

現在的作法是：列出**全部** class 檔，只跳過合成的 `$\d+`。

### 3. javac 的編碼

- 參數檔中所有路徑都要加引號（工作區路徑含空格）
- 要加 `-encoding UTF-8`，否則系統編碼（cp950）會讀壞中文
- 要加 `-source 8 -target 8`（`-bootclasspath` 與 `target 17` 不相容）

### 4. 網頁後台的中文

HTML 標籤改用 `\uXXXX` 轉義寫在 Java 字串裡，**原始碼保持純 ASCII**，
避免任何編輯器或編譯器猜錯編碼。

### 5. `aapt2 link` 需要 `--auto-add-overlay`

否則 `resource string/app_name does not override an existing resource`。

---

## 目前狀態

| 功能 | 狀態 |
|---|---|
| 建置流程（無 Gradle） | ✅ 可用 |
| 安裝、啟動 | ✅ 29 KB，不閃退 |
| 網頁後台 / 儀表板 | ✅ 實測 200 |
| 帳號管理 | ✅ 實測建立 player / alice |
| 資料包匯入 | ✅ 實測匯入 chapters |
| 遊戲伺服器（CM 協定） | ✅ 已實作，含第 14/15 章 |
| DNS 應答 | ✅ 已實作 |
| TCP relay（tun ↔ socket） | ✅ 已實作（精簡版） |
| **VPN 授權** | ⏳ 需使用者在系統對話框按「確定」（正常行為） |

---

## 尚未完成 / 已知限制

1. **HTTPS patch 伺服器還沒接上。**
   遊戲會先抓 `https://download.closersrt.com/Patch_Live/service-live.txt`。
   要服務它需要 TLS 憑證。目前 `TunRelay` 對 443 只記錄並 RST。
   好消息是上機實測顯示**patch 失敗不致命** —— 引擎仍會繼續啟動。

2. **TCP relay 是精簡版。**
   跳過了 TCP options（window scaling、SACK）、重組、重傳、分段。
   對這個「單一長連線 + 小量請求回應」的協定通常夠用，但需要實測驗證。

3. **`service-live.txt` 的格式還沒實機驗證。**
   目前 `server/content/Patch_Live/service-live.txt` 是推測格式。

---

## 檔案

```
proxy_app/
  AndroidManifest.xml
  res/values/strings.xml
  src/com/closersrt/proxy/
    MainActivity.java      控制面板
    ProxyVpnService.java   VpnService（建立 tun）
    TunRelay.java          userspace TCP/IP relay（核心）
    Dns.java               DNS 應答
    GameServer.java        CM 協定伺服器（含 14/15 章）
    LocalWebServer.java    網頁後台
    UserManager.java       帳號
    ProfileStore.java      資料包
    Status.java            日誌 + 狀態 JSON
tools/
  build_proxy_apk.py       無 Gradle 建置
```
