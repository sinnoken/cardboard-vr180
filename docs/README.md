# 文件索引

每種規格只寫在一份文件，其他地方用連結指過去。新增文件時一併更新這張表。

| 文件 | 負責的規格 |
|---|---|
| [../README.md](../README.md) | 使用者文件：安裝（GitHub Actions 下載、固定簽章、Android Studio）、網路與權限、第一次使用與各項校正的步驟、**按鍵對應**（「操作」一節，`ControllerProfile.kt` 的註解指向這裡）、影片來源、影片清單選項、**檔案慣例**（副檔名、解析度標記、場景校正檔檔名）、轉檔指令、測試影片用法、已知限制 |
| [architecture.md](architecture.md) | 元件與職責、執行緒模型、播放資料流（Intent extras、緩衝、版本選擇與降版）、頭部追蹤、渲染流程（鏡片中心、畸變公式、場景旋轉、測試圖樣、選單面板）、輸入處理（連發、單手扳機去重、動作分派、焦點規則） |
| [data-formats.md](data-formats.md) | App 私有目錄裡每個 JSON 檔（`device.json`、`lenses.json`、`connection.json`、`folders.json`、`controller.json`、`browse.json`、`scenes/`）與影片旁 `*.vr180.json` 的欄位、型別、預設值、缺檔與缺欄位時的行為、讀寫時機；鏡片預設檔數值 |
| [development.md](development.md) | 工具鏈與相依套件版本的來源檔、建置設定重點、命令列建置、單元測試範圍、版本號規則、CI 流程、發布流程、`tools/make_test_video.sh` 的輸出規格 |

版本號（AGP、Gradle、SDK、函式庫）不抄進文件，以 `gradle/libs.versions.toml`、`gradle/wrapper/gradle-wrapper.properties`、
`app/build.gradle.kts` 為準。
