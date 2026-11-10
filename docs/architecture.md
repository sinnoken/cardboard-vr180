# 架構

元件分工、執行緒模型、播放資料流、渲染與輸入的運作方式。程式碼都在
`app/src/main/java/com/example/vr180/`，下文的路徑都相對於這裡。
設定檔與場景校正檔的格式見 [data-formats.md](data-formats.md)；按鍵對應見 [README 的「操作」](../README.md#操作)。

## 元件與職責

```
NAS ──SMB──▶ SmbDataSource（4 MiB 區塊讀取）──┐
本機資料夾 ──▶ ContentDataSource ──────────────┤ RoutingDataSource（依 URI scheme 選擇）
                                               ▼
           ExoPlayer（只允許硬體視訊解碼器；超出能力自動降版）
               │  Surface
               ▼
           SurfaceTexture / OES 外部貼圖（零複製）
               │
HeadTracker ──▶ VrRenderer 著色器：螢幕像素 → 鏡片畸變校正 → 視線方向
（預測、漂移補償）    → 頭部旋轉 → 場景校正 → VR180 取樣／校正圖樣／選單面板
```

| 檔案 | 職責 |
|---|---|
| `MainActivity.kt` | 設定與瀏覽的 Compose 畫面（`Screen` 列舉切換，沒有導覽函式庫）；按鍵測試頁開啟時攔截所有輸入事件 |
| `PlayerActivity.kt` | VR 播放畫面：建立 ExoPlayer、GLSurfaceView、輸入分派、VR 選單各頁、場景校正讀寫 |
| `model/Profiles.kt` | `LensProfile`、`DeviceCalib`、`SceneCalib`、`SmbConnection` 與其 JSON 轉換 |
| `data/ProfileStore.kt` | App 私有目錄裡所有設定檔的讀寫 |
| `source/FileSource.kt` | 影片來源介面 `FileSource`，實作 `SmbFileSource`、`LocalFileSource`（SAF） |
| `source/Sidecar.kt` | 場景校正檔位置 `SidecarRef` 與讀寫 `SidecarIo` |
| `smb/SmbRepository.kt` | 單一 NAS 的 SMB 連線（smbj）：列目錄、開檔、讀寫小檔、組 `smb://` URI |
| `smb/SmbDataSource.kt` | ExoPlayer 的 SMB 資料來源 |
| `smb/LocalNetworkPermission.kt` | Android 17 起的 `ACCESS_LOCAL_NETWORK` 執行時權限判斷 |
| `player/RoutingDataSource.kt` | `smb://` 交給 `SmbDataSource`、`content://` 交給 `ContentDataSource`，其他 scheme 丟出 `IOException` |
| `player/HardwareOnly.kt` | `MediaCodecSelector`：視訊只留硬體加速的解碼器，音訊不過濾 |
| `tracking/HeadTracker.kt` | 頭部追蹤：預測、重新置中、漂移補償 |
| `tracking/DriftMeter.kt` | 陀螺儀檢測頁的靜置漂移量測 |
| `render/VrRenderer.kt` | `GLSurfaceView.Renderer`：每幀算鏡片中心與矩陣，兩眼各畫一次 |
| `render/Shaders.kt` | 單次繪製的頂點與片段著色器 |
| `render/M3.kt` | 3×3 旋轉矩陣工具（列優先） |
| `vrui/VrMenu.kt` | 世界固定的 VR 選單面板：版面、視線判定、焦點、畫到 Bitmap |
| `input/ControllerInput.kt` | 按鍵與搖桿事件 → `VrAction`，處理連發與 A＋R2 合併 |
| `input/ControllerProfile.kt` | 按鍵配置（標準／單手）與 `ControllerChoice`（自動判斷） |
| `input/InputLog.kt` | 按鍵測試頁的事件紀錄 |
| `ui/` | Compose 畫面：首頁、瀏覽、螢幕尺寸校正、陀螺儀檢測、眼鏡設定檔、按鍵測試；`Errors.kt` 把例外轉成中文訊息 |

## 執行緒模型

### PlayerActivity

| 執行緒 | 負責 |
|---|---|
| 主執行緒 | Activity 生命週期、輸入事件、ExoPlayer 的所有呼叫與回呼、每 500 ms 更新播放時間（`clock`）、感測器事件（`registerListener` 未指定 Handler） |
| GL 執行緒 | `VrRenderer.onDrawFrame`、`VrMenu` 的全部狀態、選單各頁的動作、鏡片與場景校正的即時數值 |
| `io`（單一執行緒 Executor） | 場景校正的讀取與儲存、鏡片設定檔儲存 |
| ExoPlayer 載入執行緒 | 呼叫 `RoutingDataSource`／`SmbDataSource` 讀檔 |

跨執行緒的規則：

- 主執行緒 → GL 執行緒：`glView.queueEvent { … }`；GL 執行緒 → 主執行緒：`runOnUiThread { … }`。
  控制器動作除了 `PLAY_PAUSE` 都先送到 GL 執行緒處理。
- 渲染參數（`lens`、`scene`、`mode`、`guide`、`hasVideo`、`pxPerMm`）是 `@Volatile` 的不可變值，整個替換，不改內容。
- 播放狀態（`playing`、`posMs`、`message` 等）是 `@Volatile`，主執行緒寫、GL 執行緒讀來畫選單；
  `VrMenu.status` 由主執行緒組好字串後設定，再以 `queueEvent` 要求重畫。
- `HeadTracker` 以內部 lock 交換感測器資料，`headMatrix()` 在 GL 執行緒每幀呼叫。

### 設定與瀏覽畫面

- Compose 在主執行緒；目錄列表與 SMB 連線測試以 coroutine 在 `Dispatchers.IO` 執行。
- `ProfileStore` 的方法都是 `@Synchronized`，主執行緒的畫面與播放器的 `io` 執行緒都會呼叫。
- `SmbRepository` 是全 App 共用的單例，只保留一組 NAS 連線；`configure()`、`share()`、`closeAll()` 是
  `@Synchronized`，列目錄與讀寫在呼叫端的背景執行緒進行。

## 播放資料流

### 從瀏覽到播放器

1. `BrowserScreen` 掃描目錄（包含子資料夾時以廣度優先往下最多 3 層），只收副檔名為
   `mp4`、`mkv`、`mov`、`m4v`、`ts`、`webm` 的檔案；名稱以 `.` 開頭的項目在列目錄時就被排除。
   子資料夾讀不到時略過並計數，目前這一層讀不到則整份清單顯示錯誤。
   只有換資料夾、切換「包含子資料夾」或重試才重新掃描；排序與「隱藏小於 100 MB」套用在記憶體裡的掃描結果。
   掃描中換資料夾或改選項時，舊的掃描被取消，不顯示成讀取失敗。
2. 同一資料夾、同一基本名稱的檔案歸成一支影片，版本依解析度標記由高到低排序
   （`8K` > `6K` > `5.7K` > `4K` > 沒有標記）。標記規則見 [README 的「檔案慣例」](../README.md#檔案慣例)。
3. 點選影片時以 Intent 啟動 `PlayerActivity`（1.5 秒內重複點選只開一次）：

   | Extra | 內容 |
   |---|---|
   | `uris` | 各版本的播放位址（`smb://host/share/path` 或 SAF document URI），由高到低 |
   | `labels` | 各版本的標籤（`8K`…，沒有標記的是「原檔」） |
   | `sidecar` | 場景校正檔位置，`SidecarRef.encode()` 的 JSON 字串（`kind` 為 `smb` 或 `local`） |
   | `title` | 影片基本名稱 |
   | `playlistIndex` | 這支影片在 `Playlist.items` 的位置 |

   同時把畫面上的整份影片清單（照目前排序與篩選）放進記憶體裡的 `player/Playlist`，供播放器切換上一支／下一支。
   清單不放進 Intent（可能上百支，Intent 有大小上限）；程序被回收後清單是空的，`playlistIndex` 對不上目前的
   `uris` 時播放器就當作沒有清單。切換時重設版本、錯誤狀態與標題，並重新讀取該影片的場景校正。

   `uris` 為空時播放器直接結束。

### 播放器

- `DefaultRenderersFactory` 套用 `HardwareOnly.codecSelector`，並關閉解碼器失敗時改用其他解碼器（`setEnableDecoderFallback(false)`）。
- `DefaultLoadControl`：最少緩衝 15 秒、最多 40 秒，開始播放需 2.5 秒，重新緩衝後需 5 秒。
- `ProgressiveMediaSource` 搭配 `RoutingDataSource`，刻意不用 `DefaultDataSource`，播放器只能讀 SMB 與 SAF。
- `SmbDataSource` 一次向 NAS 讀 4 MiB 進緩衝區再分批交給 ExoPlayer，避免每次幾十 KB 的請求被網路延遲拖垮；
  smbj 單次讀取的上限由 NAS 協商決定，不足 4 MiB 會繼續讀。
- 開啟 smb 檔時，smbj 錯誤依 NT 狀態碼歸類：`STATUS_OBJECT_NAME_NOT_FOUND`／`STATUS_OBJECT_PATH_NOT_FOUND`
  → `IO_FILE_NOT_FOUND`，`STATUS_ACCESS_DENIED`／`STATUS_LOGON_FAILURE` → `IO_NO_PERMISSION`，
  其他 → `IO_NETWORK_CONNECTION_FAILED`。播放器依錯誤碼顯示原因。
- 播放器建立時，若有 `smb://` 位址就以 `connection.json` 設定 `SmbRepository`：程序被系統回收後
  直接回到播放器時，單例是空的。
- `SmbRepository` 的 smbj 設定：多協定協商、讀取緩衝 8 MiB、逾時 30 秒、使用完整版 BouncyCastle。
  建立連線前先以 6 秒逾時試連 TCP 445，位址錯誤時不必等 30 秒。帳號空白時以訪客登入。

### 開始播放

播放器開啟後先 `prepare()` 但不播放，提示面板跟著視線移動。第一個動作（不含連發）觸發開始：
返回動作結束播放器，其他動作重新置中並開始播放。

### 版本選擇與降版

- `playVariant(i)` 播第 i 個版本。只有最後一個版本設 `setExceedRendererCapabilitiesIfNecessary(true)`，
  讓超出解碼器宣告能力的軌道仍交給硬體解碼器嘗試。
- `onTracksChanged` 發現視訊軌超出宣告能力：還有較低版本就降版；已是最後一個版本則只顯示提示，照樣播放。
- `onPlayerError` 是解碼器相關錯誤（`DECODER_INIT_FAILED`、`DECODER_QUERY_FAILED`、`DECODING_FAILED`、
  `DECODING_FORMAT_EXCEEDS_CAPABILITIES`、`DECODING_FORMAT_UNSUPPORTED`）：降版；最後一個版本也失敗就停止並顯示錯誤。
  其他錯誤（網路、找不到檔案、格式損壞等）不降版，顯示錯誤並把播放鍵變成「重試」。
- 每個版本最多降版一次（`failedVariant`）。「重試」從目前版本重新 `prepare()` 並跳回原位置。
- 播完（`STATE_ENDED`）或出錯時，若選單沒開就自動打開主選單。

### 場景校正

開始時在 `io` 執行緒讀取，儲存時寫手機副本與影片旁的檔案。讀取順序、寫入方式與失敗時的行為見
[data-formats.md 的「場景校正檔」](data-formats.md#場景校正檔)。讀取完成前不能進入「這支影片的校正」。

## 頭部追蹤

`HeadTracker` 使用 `TYPE_GAME_ROTATION_VECTOR`（`SENSOR_DELAY_FASTEST`）：系統融合已估算陀螺儀零偏，
仰俯與側傾由重力鎖定；刻意不用磁力計，代價是水平方向會慢慢漂移。

- 角速度：相鄰兩個樣本的四元數差（間隔 0.1 ms–100 ms 才採用）換算成裝置座標系角速度，
  以 `ω = 0.6·ω + 0.4·ω_new` 平滑。
- 預測：`headMatrix(now)` 把方向往前推 `min(now − 最後樣本時間, 50 ms) + predictionMs`。
- 座標：依 Activity 建立時的螢幕旋轉（`ROTATION_90`／`ROTATION_270`）重新對應軸，再把 Android 世界
  （東、北、上）換成 GL 世界（x = 東、y = 上、z = 南，眼睛看 −z）。
- 重新置中：下一幀把目前面向的水平角記為 `yaw0`，並重設漂移計時起點 `tRef`。
- 漂移補償：輸出 `rotY(yaw0 + yawDriftDegPerSec·(now − tRef)) · G`。

`DriftMeter`（陀螺儀檢測頁）：按開始後等 1.5 秒，取樣 15 秒，以最小平方法擬合「水平角 vs 時間」的斜率；
螢幕法線傾角餘弦變化超過 0.02 判定手機被移動，樣本少於 50 個判定樣本不足，兩者都不存檔。

## 渲染流程

`GLSurfaceView`（OpenGL ES 3.0，連續繪製，`preserveEGLContextOnPause`），單一 shader program、
一個全螢幕四邊形，每幀兩眼各畫一次，不用中間 framebuffer，影片貼圖每個像素只取樣一次。

### 每幀（VrRenderer.onDrawFrame）

1. 有新影格時 `updateTexImage()` 並取得貼圖轉換矩陣。
2. 取頭部矩陣 `H`（世界 ← 螢幕），呼叫 `frameHook`（選單的視線判定與重畫，見下節）。
3. 計算鏡片中心（視窗像素座標，`W`、`Hpx` 是整個視窗寬高，`p` 是 `pxPerMm`）：
   - 垂直基準 `cy`：`BOTTOM` = `lensHeightMm·p`；`TOP` = `Hpx − lensHeightMm·p`；`CENTER` = `Hpx/2 + lensHeightMm·p`。
   - 兩眼半距 `s = interLensMm/2·p`，傾斜角 `θ = tiltDeg`，`side` 左眼 −1、右眼 +1：
     `cx = W/2 + offsetXMm·p + side·s·cosθ`，`cy_eye = cy + side·s·sinθ`。
     手機歪斜只移動鏡片中心，不旋轉畫面（陀螺儀量的就是手機方向）。
   - `pxPerMm`：`device.json` 有校正值（> 0）就用，否則用系統密度 `(xdpi + ydpi) / 2 / 25.4`。
4. 場景旋轉 `Rs = rotY(yawDeg)·rotX(pitchDeg)·rotZ(rollDeg)`；每眼再乘單眼修正
   `A = rotY(±convergenceDeg/2)·rotX(±eyeVerticalDeg/2)`（左眼 +、右眼 −），
   `contentFromEye = A · Rsᵀ · H`。
5. 每眼視窗是左右半個螢幕；`swapEyes` 為 `false` 時左眼取影片左半張、右眼取右半張，`true` 時對調。

### 片段著色器（Shaders.FRAGMENT）

1. `t = (gl_FragCoord.xy − 鏡片中心) / (screenToLensMm·p)`：相對鏡片中心的 tan 角。
2. 畸變校正（與 Cardboard SDK 相同）：`te = t·(1 + k1·r² + k2·r⁴)`，`r² = |t|²`。
3. `max(|te.x|, |te.y|) > tan(halfFovDeg)` 的像素最後塗黑。
4. 視線 `eye = normalize(te.x, te.y, −1)`。
5. 依 `mode` 上色：

   | mode | 名稱 | 內容 | 跟著頭轉 |
   |---|---|---|---|
   | 0 | VIDEO | `c = contentFromEye·eye`，`lon = atan(c.x, −c.z)`、`lat = asin(c.y)`；經度在 ±90° 內才取樣，`uv = (offset + (lon/π + 0.5)·0.5, lat/π + 0.5)`，`offset` 為 0 或 0.5，再乘貼圖轉換矩陣；`guide` 開啟時疊紅色地平線 | 世界固定 |
   | 1 | GRID（方格牆） | 正前方 z = −1 平面上的粗細兩層方格、綠色正前方垂直線、紅色地平線 | 世界固定 |
   | 2 | NONIUS（眼距對準圖） | 左眼只畫十字的上半與左半、右眼只畫下半與右半，加上兩眼共同的黃色參考圈 | 世界固定 |
   | 3 | CENTER（對中心） | 以 `te` 畫同心圓、十字、`tan(halfFov)·0.97` 處的黃色外框 | 固定在螢幕上 |

6. 選單開著時：視線與面板平面相交處取選單貼圖，以預乘 alpha 疊加；鏡片中心畫白色準星（黑框）。

### VR 選單面板（VrMenu）

- 面板畫在 1200×1000 的 Bitmap（Android Canvas，可直接用中文字型），有變動而且面板顯示中才重畫並上傳成貼圖；
  收起時的變動留到下次顯示再畫。GL context 重建（新的影片 Surface）時會要求重畫一次。
- 面板是世界固定的平面：中心方向 `f`、右 `r`、上 `u`，水平半寬 `tan 27°`，保持直立（`r` 在水平面上）。
  主選單放在打開時的視線方向；校正頁放在水平前方往下 36°，正中央的測試圖樣不被擋住。
- 每幀以視線與面板求交點，得到視線所在的項目、數值項目的 − / + 區（兩側各 22% 寬）、進度列上的位置（量化到 1/200）。
- 版面：標題、最多 5 行的狀態與說明（狀態在前，超過時截掉說明），之後每列平均分配寬度。

## 輸入

### 事件來源

- 按鍵：`PlayerActivity.onKeyDown/onKeyUp` → `ControllerInput`；配置每次依事件裝置名稱解析
  （`ControllerChoice.resolve`），對應不到的 keycode 交回系統（例如音量鍵）。
- 搖桿與十字鍵軸：`onGenericMotionEvent`，只看 `AXIS_HAT_X/Y` 與 `AXIS_X/Y`（取絕對值較大者），
  超過 ±0.6 視為按下。
- 觸控（Cardboard 按鈕）：輕點 = `SELECT`（標記為來自觸控），長按 = `RECENTER`。
- 系統返回（手勢或返回鍵）= `BACK`。

### ControllerInput

- 連發：只有上下左右會連發，按下 400 ms 後每 90 ms 送一次，並附上已按住的毫秒數；忽略系統的按鍵重複事件。
- 標準配置的 A 與 R2：先等 80 ms，期間收到另一個就合成一次 `TRIGGER`，否則照標準配置送出。
  這是為了沒被認出的單手控制器（扳機同時送 A＋R2）。
- 單手配置的 A、R1、R2 都是 `TRIGGER`；A 與 R2 在 80 ms 內只算一次。

### 動作分派（PlayerActivity）

| 動作 | 行為 |
|---|---|
| `PLAY_PAUSE` | 主執行緒直接處理播放鍵：失敗時重試、播完時重播，否則播放／暫停 |
| `SEEK_BACK`／`SEEK_FWD` | 在鏡片校正頁切換測試圖樣（CENTER → NONIUS → GRID 循環），其他時候跳 ∓10 秒 |
| `VOL_UP`／`VOL_DOWN` | 主執行緒調整 `STREAM_MUSIC` 音量（不顯示系統面板），目前音量寫進狀態列 |
| `TRIGGER` | 面板收起時叫回面板；選單開著或正在「設定正前方」時等同 `SELECT`；否則等同播放鍵 |
| `RECENTER` | 重新置中，選單開著時也把面板移到新的正前方 |
| `MENU` | 面板收起時叫回；校正頁開著時收起面板（保留修改，「設定正前方」中則先回到場景校正頁）；主選單開著時關閉；否則打開主選單 |
| 其他 | 見下 |

其他動作依序判斷：

1. 面板收起時，`SELECT`／`BACK` 叫回面板。
2. 「設定正前方」中，`SELECT`（觸控時視線不在任何項目上才算）把目前視線設為正前方。
3. 選單關著：
   - `SELECT`／`BACK` 打開主選單。
   - 左右鍵：鏡片校正面板收起時切換測試圖樣；否則跳轉，按住時最多每 600 ms 跳一次，
     距離依按住時間：< 2 秒 10 秒、< 4 秒 30 秒、< 6 秒 1 分、之後 2 分。
   - 上下鍵（只認剛按下，校正面板收起時不動作）：切到 `Playlist` 的上一支／下一支。
4. 觸控且視線不在任何項目上：主選單時關閉選單，校正頁時忽略（避免誤觸「全部歸零」等按鈕）。
5. 交給 `VrMenu.handle`。焦點在數值項目上按住左右時，每次連發走的格數依按住時間：
   < 1 秒 1 格、< 2 秒 3 格、< 3 秒 6 格、之後 12 格。

### VrMenu 的焦點規則

- 上下鍵依閱讀順序走到上一格／下一格（同一列有兩個數值項目時，右邊那格才走得到）。
- 左右鍵：數值項目調整數值；進度列跳轉（節流與距離跟選單關著時一樣）；按鈕在同一列內移動焦點。
- 確認：按鈕執行動作；數值項目只在視線落在同一格的 − / + 區時調整；進度列只在視線落在進度列上時跳到視線位置。
- 視線移到「另一個」項目時才搶走焦點，避免蓋掉控制器的選擇。
- 面板剛放到視線前方（打開、叫回、重新置中）時，先忽略視線所在的那一格，等視線移到別格才開始判定。
  主選單一打開時面板中心正好落在「設定…」上，這條規則讓「打開就按確認」仍然作用在播放鍵，
  「看別處點一下關閉選單」也維持原樣。

### 按鍵測試頁

`InputLog.active` 為 `true` 時，`MainActivity.dispatchKeyEvent`／`dispatchGenericMotionEvent` 記錄並攔下所有事件
（系統返回鍵除外），依測試頁選的配置顯示 App 會把它當成什麼動作；A 與 R2 在 80 ms 內先後送出時標成合併的扳機。
