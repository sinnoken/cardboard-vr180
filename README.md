# VR180 Player（Android 原生版）

從 NAS（SMB）或手機本機資料夾播放 8K VR180 左右並排影片，戴 Cardboard 類眼鏡觀看。
只用硬體解碼、App 內完成陀螺儀、鏡片、場景三種校正，支援藍芽控制器。

## 規格

| 項目 | 版本 |
|---|---|
| Android Gradle Plugin | 9.3.1（內建 Kotlin，不再套用 kotlin-android 外掛） |
| Gradle | 9.5.0 |
| Kotlin（Compose 編譯器外掛） | 2.4.20 |
| compileSdk / targetSdk | 37（Android 17） |
| minSdk | 31（Android 12） |
| Media3 ExoPlayer | 1.11.0 |
| Compose BOM | 2026.09.00 |
| smbj（SMB2/3） | 0.13.0 |
| 渲染 | OpenGL ES 3.0，單次繪製著色器 |

## 建置

1. 用最新版 Android Studio 開啟此資料夾（需支援 AGP 9.3）。
2. 專案沒有附 `gradlew` 執行檔；Android Studio 會依 `gradle/wrapper/gradle-wrapper.properties` 自動處理。
   若要用命令列，先在本機執行一次 `gradle wrapper --gradle-version 9.5.0`。
3. 接上手機執行。App 不依賴 Google Play 服務。

> 這份程式碼尚未在實機上編譯測試過。著色器已用 glslang 驗證、方向數學與設定檔格式已做數值測試，
> 但 Android 相關部分第一次建置時可能需要小修正。

## 在 GitHub 上編譯（不用裝 Android Studio）

專案已附 `.github/workflows/build.yml`，推上 GitHub 後會自動在雲端編譯。

1. 在 GitHub 建立一個新的儲存庫（建議設為私人），把整個資料夾推上去。
2. 打開儲存庫的 **Actions** 分頁，會看到「Build APK」正在執行（約 5–10 分鐘）。
3. 完成後點進該次執行，在頁面下方 **Artifacts** 下載 `VR180Player-debug`（是個 zip，裡面是 APK）。
4. 把 APK 傳到手機安裝（需允許「安裝不明來源的應用程式」）。

編譯失敗時，點開失敗的步驟就能看到錯誤訊息。

**固定簽章（選用）**：雲端每次編譯都會產生新的除錯金鑰，新版無法直接覆蓋安裝舊版。
想直接覆蓋安裝的話，在自己電腦產生一次金鑰並存進 GitHub Secrets：

```bash
keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey \
  -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
base64 -w0 debug.keystore   # 把輸出內容複製起來
```

到儲存庫 **Settings → Secrets and variables → Actions** 新增 `DEBUG_KEYSTORE_BASE64`，貼上剛才的內容。

## 網路

App 不依賴任何網際網路服務：沒有 Google Play 服務、分析、廣告或當機回報元件。
網路只用在以 SMB 連到你設定的 NAS。Android 17 起，第一次連線時會詢問「附近裝置／區域網路」權限，
允許後才能存取區網內的 NAS。

## 第一次使用的順序

設定都可以跳過，直接選影片播放即可。播放時先戴好眼鏡、看向正前方，按任意鍵或點螢幕才會開始。

1. **螢幕尺寸校正（選用）**：把信用卡直放貼在螢幕框上，調到四邊貼齊。未校正時使用手機回報的螢幕密度。
2. **陀螺儀檢測（選用）**：手機平放 15 秒不要碰，量出水平漂移速率，播放時自動抵銷。
   同頁可調「延遲預測」：轉頭時畫面拖在後面就調高，停下時衝過頭就調低。
3. **眼鏡設定檔（可跳過）**：預設直接使用 Cardboard 預設值（與 Cardboard SDK 未掃描時相同）。
   預設檔不會被修改：在 VR 裡校正後儲存，會另存成「我的眼鏡」設定檔；調壞了隨時可以改回預設值。
4. 播放任一影片，打開選單 › **設定…** › **鏡片校正**（戴著眼鏡做）：
   - 對中心（進入校正時的第一張圖）：手機不一定放在眼鏡正中間，所以改移動畫面、不用移動手機。
     一次閉一隻眼，調「左右位置」「上下位置」讓同心圓的圓心落在鏡片正中央、黃色外框四邊被鏡片邊緣切掉一樣多。
     若一眼的圓心偏高、另一眼偏低，是手機在眼鏡裡歪了，調「傾斜」。
   - 眼距對準圖：調「鏡片間距」到十字上下兩段接成直線、左右兩段對齊。
   - 方格牆：看正前方調 k1 讓線條變直，看邊緣調 k2；最後左右轉頭，方格要像釘在牆上，
     若跟著頭跑或反向滑動，調「螢幕到鏡片」。
   - 調亂了可以按「Cardboard 預設值」一鍵回到起點（按「儲存」才會寫入）。
5. 只有某支影片拍歪時，才需要 **設定… › 這支影片的校正**：
   「設定正前方」看著主角點一下，就同時設好水平方向與仰俯；地平線歪了調「地平線」。
   雙眼垂直差、匯聚、左右眼對調在「進階」。一定先完成鏡片校正，再做這一步。

## 操作

選單是固定在空間中的面板。校正頁的面板放在視線下方，正中央的測試圖樣不會被擋住。

| 藍芽控制器 | 動作 |
|---|---|
| D-pad / 左搖桿 / 方向鍵 | 移動焦點；數值項目左右鍵直接調整（按住連發） |
| A / 確認 / Enter | 選取 |
| B / 返回 / Esc | 返回；校正頁按 B 會放棄修改 |
| Start / Y / Menu | 開關選單 |
| X / Select / 左搖桿按下 | 重新置中 |
| L1 / R1、上一首 / 下一首 | 倒退 / 快轉 10 秒 |
| 播放暫停鍵 / 空白鍵 / R2 | 播放 / 暫停 |
| 選單關閉時左右鍵 | 倒退 / 快轉 10 秒 |

藍芽手把、D-pad 遙控器、多媒體遙控器、鍵盤都可以，先在系統設定配對即可，App 不需要藍芽權限。

沒有控制器時：看著選單項目按 Cardboard 按鈕（= 觸碰螢幕）選取，數值項目看著 − 或 + 再按；
長按畫面重新置中。

## 本機資料夾

主畫面的「新增本機資料夾」會開啟系統的資料夾選擇器。手機儲存空間、SD 卡、USB 隨身碟都可以，
授權一次後 App 會記住。App 只能存取選定的資料夾，不需要儲存權限，播放時也不用網路。

本機播放也很適合用來排查問題：同一支片在本機順、從 NAS 卡，代表瓶頸在網路傳輸；
兩邊都卡，代表是解碼或過熱。

## 測試影片

`tools/make_test_video.sh` 會產生一支 10 秒的 4K 測試片（左眼藍底、右眼紅底，黃線是正前方，
標示上下左右 60°），用來確認左右眼沒接反、方向正確、轉頭時畫面固定。需要 ffmpeg。
現成的檔案附在 GitHub Releases（`vr180_test_4K.mp4`）。

## 檔案慣例（NAS 與本機資料夾相同）

- 格式：等距投影（equirectangular）左右並排 VR180，左半張給左眼。相機直出的魚眼格式需先轉檔。
- 多解析度版本：檔名結尾加標記，會自動歸成同一支影片，播放時挑手機能硬解的最高版本：
  `trip_8K.mp4`、`trip_5.7K.mp4`、`trip_4K.mp4`（支援 8K / 6K / 5.7K / 4K）。
- 場景校正存在影片旁的 `trip.vr180.json`（所有版本共用、換手機也帶得走）。
  資料夾唯讀時只存在手機。

### 轉出 5.7K 手機版（ffmpeg）

手機在 Cardboard 上的實際解析度大約落在 5.7K 左右，8K 多出的細節幾乎看不到，卻最容易超出硬解能力。

```bash
# CPU（品質最好）
ffmpeg -i trip_8K.mp4 -vf scale=5760:2880:flags=lanczos \
  -c:v libx265 -crf 20 -preset slow -pix_fmt yuv420p10le -tag:v hvc1 \
  -c:a copy trip_5.7K.mp4

# NVIDIA 顯示卡（快很多）
ffmpeg -hwaccel cuda -i trip_8K.mp4 -vf scale=5760:2880:flags=lanczos \
  -c:v hevc_nvenc -preset p5 -cq 22 -pix_fmt p010le -tag:v hvc1 \
  -c:a copy trip_5.7K.mp4
```

## 架構

```
NAS ──SMB──▶ SmbDataSource（4 MB 區塊讀取）──┐
本機資料夾 ──▶ ContentDataSource ─────────────┤ RoutingDataSource（依位址類型選擇）
                                              ▼
           ExoPlayer（只允許硬體視訊解碼器；超出能力自動降版）
               │  Surface
               ▼
           SurfaceTexture / OES 外部貼圖（零複製）
               │
HeadTracker ──▶ VrRenderer 著色器：螢幕像素 → 鏡片畸變校正 → 視線方向
（預測、漂移補償）    → 頭部旋轉 → 場景校正 → VR180 取樣 / 校正圖樣 / 選單面板
```

| 檔案 | 內容 |
|---|---|
| `model/Profiles.kt` | 鏡片、裝置、場景校正的資料格式（JSON，之後 PWA 版共用） |
| `source/` | 影片來源介面（NAS／本機資料夾）、場景校正檔讀寫 |
| `smb/` | SMB 連線、ExoPlayer 資料來源 |
| `player/RoutingDataSource.kt` | 依位址類型選擇 SMB 或本機讀取 |
| `player/HardwareOnly.kt` | 只允許硬體視訊解碼器 |
| `tracking/` | 頭部追蹤、靜置漂移量測 |
| `render/` | 著色器與渲染器 |
| `vrui/VrMenu.kt` | VR 內選單（控制器焦點、視線、單鍵） |
| `input/ControllerInput.kt` | 藍芽控制器按鍵對應 |
| `ui/` | Compose 設定畫面 |

## 已知限制

- SMB 密碼以明文存在 App 私有目錄。
- 只支援左右並排；上下並排或魚眼需另外加。
- UI 面板與準星在兩眼位置相同（位於無限遠），近距離內容上可能有深度衝突。
- 頭部只有旋轉追蹤（3DoF），不支援前後移動。
