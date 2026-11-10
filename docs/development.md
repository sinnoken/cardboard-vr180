# 開發與建置

工具鏈、命令列建置與 CI。使用者用的安裝步驟（Android Studio、從 GitHub Actions 下載 APK、固定簽章）在
[README 的「安裝」](../README.md#安裝)。

## 工具鏈與相依套件

版本只記在下列檔案，以它們為準：

| 項目 | 來源 |
|---|---|
| Android Gradle Plugin、Kotlin（只用於 Compose 編譯器外掛）、Compose BOM、Media3、smbj 等函式庫 | `gradle/libs.versions.toml` |
| Gradle | `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl`；CI 用的版本寫在 `.github/workflows/build.yml` |
| compileSdk、targetSdk、minSdk、Java／JVM target、`versionCode`／`versionName` | `app/build.gradle.kts` |
| 權限、必要硬體（陀螺儀、OpenGL ES 3.0）、Activity 設定 | `app/src/main/AndroidManifest.xml` |

建置設定的重點：

- AGP 9 內建 Kotlin 支援，`app/build.gradle.kts` 不套用 `kotlin-android` 外掛。
- Media3 的 `DataSource`、`MediaCodecSelector` 屬於 `@UnstableApi`，以 lint 的 `disable += "UnsafeOptInUsageError"` 放行。
- smbj 依賴的 BouncyCastle 會帶進重複的 `META-INF` 檔案，在 `packaging.resources.excludes` 排除。
- 沒有 Google Play 服務、分析、廣告或當機回報相依套件；新增相依套件時維持這點。

## 命令列建置

專案沒有附 `gradlew`。第一次用命令列建置前，在本機用已安裝的 Gradle 產生 wrapper，版本取
`gradle-wrapper.properties` 裡的那個：

```bash
gradle wrapper --gradle-version <gradle-wrapper.properties 的版本>
./gradlew assembleDebug
```

APK 輸出在 `app/build/outputs/apk/debug/`。本機建置沒有傳版本號時，`versionName` 是 `0.0.0-dev`、`versionCode` 是 1。

## 單元測試

`app/src/test/` 是 JVM 單元測試（JUnit 4），涵蓋不需要 Android 執行環境的純邏輯：

- 播放器的計算（跳轉距離、連發格數、時間格式、角度換算、鏡片上下位置）：`player/PlayerMath.kt`。
- 檔名的解析度標記與版本優先順序：`source/VariantNames.kt`。
- 控制器按鍵配置與自動選擇：`input/ControllerProfile.kt`。
- 設定檔 JSON 的來回轉換與缺欄位預設值：`model/Profiles.kt`。

android.jar 裡的 `org.json` 只是空殼，測試另外相依 `org.json:json`。命令列執行：`./gradlew testDebugUnitTest`。
畫面、GL 與硬體相關的行為沒有自動化測試，要在實機上驗證。

## 版本號

由 CI 從 git 算出，以 Gradle 屬性 `appVersionName`、`appVersionCode` 傳給 `app/build.gradle.kts`：

- 取最近的 `vX.Y.Z` tag 與之後的 commit 數 `n`（`git describe --tags --long`）。
- `versionName`：`n = 0` 時是 `X.Y.Z`，否則是 `X.Y.Z-dev.n`。
- `versionCode`：`(X × 10000 + Y × 100 + Z) × 100 + n`，`n` 最多算到 99。後面的版本一定比前面大，可以直接覆蓋安裝。
- 找不到 tag 時是 `0.0.0-dev`、1。

## CI（.github/workflows/build.yml）

- 觸發：每次 push（含 tag）、pull request，以及在 Actions 頁面手動執行（`workflow_dispatch`）。
- 步驟：安裝 JDK 21、Android SDK、指定版本的 Gradle（含快取），用 `glslangValidator` 驗證 `render/Shaders.kt`
  裡的兩個著色器（它們在 App 執行時才編譯，Gradle 抓不到語法錯誤），算出版本號，
  執行 `gradle testDebugUnitTest assembleDebug`，把 `app/build/outputs/apk/debug/*.apk` 上傳成 artifact `VR180Player-debug`。
- 固定簽章：Secret `DEBUG_KEYSTORE_BASE64` 有值時，解碼成 `~/.android/debug.keystore`，讓每次建置的簽章相同；
  金鑰的產生方式見 [README](../README.md#固定簽章選用)。

## 發布

推 `vX.Y.Z` tag 時，CI 在編譯完成後把 APK 改名成 `VR180Player-X.Y.Z-debug.apk`，附到同名的 GitHub Release
（已經有就覆蓋同名檔案，沒有就建立一個預先發行版）。沒有設定 `DEBUG_KEYSTORE_BASE64` 時這一步會失敗，
因為簽章不同的 APK 不能覆蓋安裝舊版。

要附上自己寫的說明時，先建立 Release 再讓它產生 tag：

```bash
gh release create vX.Y.Z --target <commit> --prerelease --title "vX.Y.Z（除錯版）：…" --notes-file notes.md
```

tag 一建立就會觸發 CI，APK 會自動附上去。

## 測試工具

`tools/make_test_video.sh [輸出檔名]` 用 ffmpeg 產生 VR180 等距投影左右並排測試片：

- 每眼 1920×1920（180°×180°），總共 3840×1920、30 fps、10 秒，H.264（High profile、level 5.1）、無音訊。
- 左眼藍底、右眼紅底，每 30° 一條格線，黃色十字線是正前方，標示 `LEFT 60`、`RIGHT 60`、`UP 60`、`DOWN 60`
  與經過秒數。
- 需要 ffmpeg 與 DejaVu 字型（腳本寫死 `/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf`）。

使用者怎麼用這支測試片見 [README 的「測試影片」](../README.md#測試影片)。
