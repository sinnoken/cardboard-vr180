# 資料格式

App 寫出的所有檔案：App 私有目錄裡的設定檔，以及影片旁的場景校正檔（sidecar）。
這些格式已部署在使用者手機與 NAS 上，改欄位名稱、型別或預設值都要相容舊檔。

程式來源：`model/Profiles.kt`（欄位與預設值）、`data/ProfileStore.kt`（檔名、讀寫規則）、
`source/Sidecar.kt` 與 `source/FileSource.kt`（sidecar 位置與讀寫）。

## 共通規則

### 位置

- 設定檔都在 `filesDir/vr180/`（即 `/data/data/com.example.vr180/files/vr180/`），
  每個場景校正的手機副本在其下的 `scenes/`。兩個資料夾在 `ProfileStore` 建立時自動建立。
- `AndroidManifest.xml` 設 `allowBackup="false"`：這些檔案不進 Android 備份，解除安裝即刪除。
- 影片旁的 `*.vr180.json` 見[場景校正檔](#場景校正檔)。

### 寫入

- 內容是 `JSONObject.toString(2)`：UTF-8、縮排 2 格。
- 所有檔案（頂層設定檔與 `scenes/` 底下的檔案）都先寫同目錄的 `<檔名>.tmp`，再 `renameTo` 成正式檔名，
  避免寫到一半斷電弄壞檔案。`renameTo` 的結果沒有檢查：改名失敗時正式檔維持舊內容，`.tmp` 留在原地。
- 每次寫入都重寫整個檔案，只包含下表列出的欄位；檔案裡多出的其他欄位在下次寫入時消失。
- `Float` 欄位以 `toDouble()` 寫出，數值可能帶浮點尾數（例如 `0.30000001192092896`）。

### 讀取

- 檔案不存在，或內容不是合法的 JSON 物件：整個檔案視同不存在，全部欄位用預設值。
- 欄位不存在：用該欄位的預設值（下表）。
- 型別：讀取用 Android `org.json` 的 `optDouble`／`optBoolean`／`optString`。
  數字字串會轉成數字，`"true"`／`"false"`（不分大小寫）會轉成布林，數字讀成字串時轉成文字；
  無法轉換時用預設值。值為 JSON `null` 時，數字與布林欄位用預設值，字串欄位讀成 `"null"`。
- 列舉值（`alignment`、`choice`、`sort`）必須與列舉名稱完全相同（大小寫須一致），否則用預設值。
- 數值範圍不檢查：檔案裡的任何數字都照用，只有在 App 介面調整時才限制範圍。
- 例外：`lenses.json` 的 `profiles`、`folders.json` 的 `folders` 陣列裡若有元素不是 JSON 物件，
  讀取會丟出 `JSONException`，這個例外沒有被攔截。

## device.json

手機本身的校正（`DeviceCalib`）。

| 欄位 | 型別 | 預設值 | 意義 |
|---|---|---|---|
| `pxPerMm` | number | `0` | 螢幕每毫米幾個像素。`≤ 0` 視為未校正，改用系統回報的密度 `(xdpi + ydpi) / 2 / 25.4` |
| `yawDriftDegPerSec` | number | `0` | 靜置量到的水平漂移速率（度／秒），播放時線性抵銷。`0` 在首頁顯示為「尚未檢測」 |
| `predictionMs` | number | `25` | 頭部方向往前預測的時間（毫秒） |
| `fullRecenter` | boolean | `false` | 置中方式：`false` 只轉水平，`true` 全方向 |

寫入時機：

- 螢幕尺寸校正按「儲存」：只改 `pxPerMm`，值為系統密度 × 滑桿倍率（0.85–1.15）。
- 陀螺儀檢測完成且結果有效：只改 `yawDriftDegPerSec`。
- 延遲預測滑桿放開：只改 `predictionMs`（介面範圍 0–60）。
- 播放器「設定…」切換置中方式：只改 `fullRecenter`，馬上生效。

播放器在開始播放時讀一次，播放中不重讀（置中方式在播放器裡切換時同時改記憶體裡的值）。

## lenses.json

眼鏡（鏡片）設定檔清單與目前使用的設定檔。

```json
{
  "activeId": "preset-cardboard-v1",
  "profiles": [ { "id": "…", "name": "…", "interLensMm": 60.0, … } ]
}
```

| 欄位 | 型別 | 預設值 | 意義 |
|---|---|---|---|
| `activeId` | string | `"preset-cardboard-v1"` | 使用中的設定檔 id，可以是預設檔 id |
| `profiles` | array of object | `[]` | 使用者的設定檔，格式見下表；不含預設檔 |

讀取規則：

- 預設檔一律以程式內的數值為準，排在清單最前面；`profiles` 裡 `id` 以 `preset-` 開頭的元素被忽略。
- `activeId` 不存在、為空字串，或對不到清單裡任何 id：改用 `preset-cardboard-v1`。
- 寫入時只寫 `id` 不以 `preset-` 開頭的設定檔，所以預設檔永遠不會出現在檔案裡。

寫入時機：

- 首頁「改用 Cardboard 預設值」、眼鏡設定檔頁選擇或「使用 Cardboard 預設值」：只改 `activeId`。
- 眼鏡設定檔頁「刪除」：移除該設定檔（使用中的設定檔不能刪）。
- VR 鏡片校正按「儲存」：使用中的是預設檔時，以新的隨機 UUID 與名稱「我的眼鏡（以 <預設檔名稱> 為基礎）」
  新增一筆；否則覆寫同 id 的那筆。兩種情況都把 `activeId` 設成它。

### 鏡片設定檔（LensProfile）

距離單位一律是毫米。

| 欄位 | 型別 | 讀取時的預設值 | VR 校正頁可調範圍 | 意義 |
|---|---|---|---|---|
| `id` | string | 每次讀取產生新的隨機 UUID | — | 識別碼。`preset-` 開頭保留給預設檔 |
| `name` | string | `"未命名"` | — | 顯示名稱 |
| `interLensMm` | number | `63` | 45–80 | 兩鏡片中心距離（「鏡片間距」） |
| `screenToLensMm` | number | `40` | 20–70 | 螢幕到鏡片距離，決定畫面比例 |
| `lensHeightMm` | number | `31` | 見下 | 鏡片中心的垂直位置，意義依 `alignment` |
| `alignment` | string | `"BOTTOM"` | 三者循環 | `"BOTTOM"`、`"CENTER"`、`"TOP"` |
| `k1` | number | `0.3` | −0.5–1.5 | 徑向畸變係數 |
| `k2` | number | `0.2` | −0.5–1.5 | 徑向畸變係數 |
| `halfFovDeg` | number | `50` | 30–75 | 單眼可視半角（度），超出部分塗黑 |
| `offsetXMm` | number | `0` | −30–30 | 兩眼畫面一起左右平移，正 = 往右 |
| `tiltDeg` | number | `0` | −10–10 | 兩鏡片中心連線相對螢幕水平的角度（度），正 = 右眼較高 |

- `lensHeightMm`：`BOTTOM` 是鏡片中心到螢幕下緣的距離，`TOP` 是到上緣的距離（兩者介面上限制 ≥ 0）；
  `CENTER` 是相對螢幕中心的上下偏移，正 = 往上（介面上限制 −30–30）。
- `k1`、`k2` 沿用 Cardboard 的定義，半徑以 tan 角度為單位，公式見 [architecture.md](architecture.md#渲染流程)。
- 缺 `id` 的設定檔每次讀取都會拿到不同的 id，`activeId` 對不到它，使用中會退回 Cardboard 預設值。
- 讀取時的預設值是缺欄位時的備援，與任何預設檔都不同。

### 預設檔

寫在 `LensProfile.presets()`，不存檔。`offsetXMm`、`tiltDeg` 都是 `0`。

| `id` | `name` | `interLensMm` | `screenToLensMm` | `lensHeightMm` | `alignment` | `k1` | `k2` | `halfFovDeg` |
|---|---|---|---|---|---|---|---|---|
| `preset-cardboard-v1` | Cardboard 預設值 | 60 | 42 | 31 | `BOTTOM` | 0.441 | 0.156 | 40 |
| `preset-cardboard-v2` | Cardboard v2（近似） | 63.9 | 39.3 | 31 | `BOTTOM` | 0.34 | 0.55 | 50 |
| `preset-generic-center` | 通用（螢幕置中） | 63 | 40 | 0 | `CENTER` | 0.30 | 0.20 | 50 |

## connection.json

NAS 的 SMB 連線資訊（`SmbConnection`）。

| 欄位 | 型別 | 預設值 | 意義 |
|---|---|---|---|
| `host` | string | `""` | 主機 IP 或名稱 |
| `share` | string | `""` | 共用資料夾名稱 |
| `user` | string | `""` | 帳號；空白（含只有空白字元）時以訪客登入 |
| `password` | string | `""` | 密碼，**明文** |
| `domain` | string | `""` | 網域 |

- 首頁按「連線並瀏覽」時，在連線之前就寫入（連線失敗也會保留）。
- 首頁沒有網域欄位，寫入時 `domain` 一律是 `""`；手動寫進檔案的網域會在下次連線時被清掉。
- `host`、`share` 在輸入時去掉前後空白；`user`、`password` 原樣保存。

## folders.json

使用者授權過的本機資料夾。

```json
{ "folders": [ { "uri": "content://…/tree/…", "name": "Movies" } ] }
```

| 欄位 | 型別 | 預設值 | 意義 |
|---|---|---|---|
| `folders` | array of object | `[]` | 依加入順序排列 |
| `folders[].uri` | string | `""` | SAF tree URI（`OpenDocumentTree` 回傳值的字串） |
| `folders[].name` | string | `""` | 顯示名稱，加入時從文件提供者讀取，讀不到時用 URI 最後一段或「本機資料夾」 |

- 加入時取得持久授權（先試讀寫，失敗改只讀）；同一個 `uri` 再加入一次會移到清單最後。
- 「移除」會釋放持久授權並從清單刪除。
- 檔案只記 URI，授權本身由系統保存；授權失效時瀏覽會顯示錯誤，要移除後重新加入。

## controller.json

控制器按鍵配置的選擇（`ControllerChoice`）。

| 欄位 | 型別 | 預設值 | 意義 |
|---|---|---|---|
| `choice` | string | `"AUTO"` | `"AUTO"`、`"STANDARD"`、`"ONE_HAND"`；其他值視為 `"AUTO"` |

在控制器按鍵測試頁切換時寫入。`AUTO` 的判斷方式與各配置的按鍵對應見 [README 的「操作」](../README.md#操作)。

## browse.json

影片清單的顯示選項，NAS 與本機資料夾共用。

| 欄位 | 型別 | 預設值 | 意義 |
|---|---|---|---|
| `sort` | string | `"NAME"` | `"NAME"`、`"DATE"`、`"SIZE"`；其他值視為 `"NAME"` |
| `descending` | boolean | `NAME` 是 `false`，`DATE`、`SIZE` 是 `true` | 是否反向排序；缺欄位時用 `sort` 的預設方向 |
| `includeSubfolders` | boolean | `false` | 是否列出子資料夾裡的影片 |
| `hideSmall` | boolean | `true` | 是否隱藏小於 100 MB 的影片 |

任一選項切換時寫入全部四個欄位。選項的行為見 [README 的「影片清單」](../README.md#影片清單)。

## scenes/

每支影片的場景校正在手機上的副本，內容格式與[場景校正檔](#場景校正檔)完全相同。

- 檔名是 `<SHA-1>.json`：鍵值以 UTF-8 編碼後做 SHA-1，寫成 40 個小寫十六進位字元。
- 鍵值（`SidecarRef.key`）：
  - NAS：場景校正檔的 SMB URI 字串，例如 `smb://nas/video/trip/trip.vr180.json`。
    URI 由 `Uri.Builder` 產生，路徑會做百分比編碼；主機用連線設定裡輸入的字串，
    所以同一個 NAS 用 IP 與用名稱連線會得到不同的鍵值。
  - 本機資料夾：`local:<tree URI>|<所在資料夾的 documentId>|<影片基本名稱>.vr180.json`。
  - 播放器沒有收到場景校正檔位置時（目前的瀏覽畫面一定會給），改用最高解析度版本的播放位址。
- 每次在 VR 裡按「儲存」都會寫入；不會自動刪除。

## 場景校正檔

一支影片的場景校正（`SceneCalib`），存在影片所在的資料夾，所有解析度版本共用。

### 位置與檔名

`<基本名稱>.vr180.json`，基本名稱是影片檔名去掉副檔名、再去掉解析度標記。
例如 `trip_8K.mp4`、`trip_4K.mp4`、`trip.mp4` 都對應 `trip.vr180.json`。
解析度標記的規則見 [README 的「檔案慣例」](../README.md#檔案慣例)。

### 欄位

| 欄位 | 型別 | 預設值 | VR 校正頁每格 | 意義 |
|---|---|---|---|---|
| `version` | number | — | — | 寫入時固定為 `1`，讀取時忽略 |
| `yawDeg` | number | `0` | 1 | 影片正前方的水平方向（度） |
| `pitchDeg` | number | `0` | 0.5 | 拍攝時攝影機的仰俯（度） |
| `rollDeg` | number | `0` | 0.2 | 地平線傾斜（度） |
| `eyeVerticalDeg` | number | `0` | 0.05 | 左右眼垂直落差（度），兩眼各分一半 |
| `convergenceDeg` | number | `0` | 0.1 | 左右眼水平位移（度），兩眼各分一半 |
| `swapEyes` | boolean | `false` | — | `true` 時左眼取右半張、右眼取左半張 |

- 角度欄位在 VR 校正頁調整時會繞回 −180–180；「設定正前方」直接算出 `yawDeg` 與 `pitchDeg`。
- 角度如何套用到畫面見 [architecture.md](architecture.md#渲染流程)。

### 讀取順序

開始播放時在背景讀取，依序採用第一個成功的：

1. 影片旁的場景校正檔：存在、讀得到、而且是合法的 JSON 物件。
2. `scenes/` 裡的手機副本。
3. 全部預設值。

讀取場景校正檔時的任何錯誤（網路、權限、格式）都視同檔案不存在。
影片旁的檔案優先：即使手機副本比較新（例如後來資料夾變成唯讀），仍採用影片旁的那一份。

### 寫入

在 VR「這支影片的校正」按「儲存」時，在背景依序：

1. 寫入 `scenes/` 的手機副本（一定寫）。
2. 寫入影片旁的場景校正檔：
   - NAS：以 `FILE_OVERWRITE_IF` 開檔，存在就覆寫、不存在就建立。
   - 本機資料夾：在所在資料夾找顯示名稱相同的檔案覆寫（`"wt"` 模式）；找不到就以 MIME
     `application/json` 建立新檔。
3. 結果顯示在選單狀態列：成功是「已存到手機與影片資料夾」，第 2 步失敗（唯讀共用資料夾、
   沒有寫入授權等）是「影片資料夾無法寫入，校正只存在手機」。
