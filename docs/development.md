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

APK 輸出在 `app/build/outputs/apk/debug/`。

## CI（.github/workflows/build.yml）

- 觸發：每次 push、pull request，以及在 Actions 頁面手動執行（`workflow_dispatch`）。
- 步驟：安裝 JDK 21、Android SDK、指定版本的 Gradle（含快取），執行 `gradle assembleDebug --stacktrace`，
  把 `app/build/outputs/apk/debug/*.apk` 上傳成 artifact `VR180Player-debug`。
- 固定簽章：Secret `DEBUG_KEYSTORE_BASE64` 有值時，解碼成 `~/.android/debug.keystore`，讓每次建置的簽章相同；
  金鑰的產生方式見 [README](../README.md#固定簽章選用)。

## 測試工具

`tools/make_test_video.sh [輸出檔名]` 用 ffmpeg 產生 VR180 等距投影左右並排測試片：

- 每眼 1920×1920（180°×180°），總共 3840×1920、30 fps、10 秒，H.264（High profile、level 5.1）、無音訊。
- 左眼藍底、右眼紅底，每 30° 一條格線，黃色十字線是正前方，標示 `LEFT 60`、`RIGHT 60`、`UP 60`、`DOWN 60`
  與經過秒數。
- 需要 ffmpeg 與 DejaVu 字型（腳本寫死 `/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf`）。

使用者怎麼用這支測試片見 [README 的「測試影片」](../README.md#測試影片)。
