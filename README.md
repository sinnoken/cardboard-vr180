[繁體中文](README.zh-TW.md)

# VR180 Player (native Android)

Plays 8K VR180 side-by-side videos from a NAS (SMB) or a local folder on the phone, viewed through a Cardboard-style viewer.
Hardware decoding only. Gyro, lens, and scene calibration are all done in the app. Bluetooth controllers are supported.

Requires Android 12 or later, a gyroscope, and OpenGL ES 3.0. The maximum playable resolution depends on the phone's hardware decoder.

Developer documentation (architecture, data formats, build details) is in [docs/](docs/README.md).

## Installation

### Build on GitHub (no Android Studio needed)

The project includes `.github/workflows/build.yml`. After you push to GitHub, it builds automatically in the cloud.

1. Create a new repository on GitHub (private recommended) and push the whole folder.
2. Open the repository's **Actions** tab. "Build APK" is running (about 5–10 minutes).
3. When it finishes, open that run and download `VR180Player-debug` under **Artifacts** at the bottom of the page (a zip that contains the APK).
4. Copy the APK to the phone and install it (allow "Install unknown apps").

If the build fails, open the failed step to see the error message.

### Fixed signing key (optional)

Each cloud build generates a new debug key, so a new version cannot be installed over an old one.
To install over the old version, generate a key once on your computer and store it in GitHub Secrets:

```bash
keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey \
  -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
base64 -w0 debug.keystore   # copy the output
```

In the repository, go to **Settings → Secrets and variables → Actions**, add `DEBUG_KEYSTORE_BASE64`, and paste the output.

### Build with Android Studio

1. Open this folder with the latest Android Studio (it must support the AGP version the project uses; see `gradle/libs.versions.toml`).
2. The project does not include a `gradlew` executable. Android Studio handles it from `gradle/wrapper/gradle-wrapper.properties`.
   For command-line builds, see [docs/development.md](docs/development.md#command-line-build).
3. Connect the phone and run.

## Network and permissions

The app depends on no internet services: no Google Play services, analytics, ads, or crash reporting.
The network is used only to reach your NAS over SMB. From Android 17, the first connection asks for the "Nearby devices / Local network" permission.
The app can reach a NAS on the LAN only after you allow it. If you deny it, enable it in system Settings › Apps › VR180 Player › Permissions.

Local folders give access only to the folders you pick. No storage permission is needed. Bluetooth controllers need no Bluetooth permission.

## First-time setup

All setup steps are optional. You can pick a video and play it right away. On playback, put on the viewer, look straight ahead, and press any button or tap the screen to start
(pressing Back at this point exits playback).

1. **Screen size calibration (optional)**: Hold a credit card upright against the screen and adjust until all four edges line up. Without calibration, the screen density reported by the phone is used.
2. **Gyro check (optional)**: Lay the phone flat and leave it untouched for 15 seconds. This measures the yaw drift rate, which is cancelled out during playback.
   The same page has "Motion prediction": raise it if the image lags behind when you turn your head; lower it if the image overshoots when you stop.
3. **Viewer profiles (can be skipped)**: By default, Cardboard defaults are used (the same as Cardboard SDK without a scanned viewer).
   Presets are never modified: if you start from a preset, calibrate in VR, and save, the result is saved as a new "My viewer (based on …)" profile and activated.
   Later calibrations update this profile directly. If you mess it up, switch back to the defaults on the home screen at any time.
4. Play any video, open the menu › **Settings…** › **Lens calibration** (do this wearing the viewer):
   - Centering (the first pattern when calibration opens): The phone is not always centered in the viewer, so you move the image instead of the phone.
     Close one eye at a time. Adjust "Horizontal offset" and "Vertical position" so the center of the concentric circles sits in the middle of the lens, and the lens edge cuts off the same amount of the yellow frame on all four sides.
     If the center is high in one eye and low in the other, the phone is tilted in the viewer. Adjust "Tilt".
   - IPD alignment: Adjust "Lens separation" until the upper and lower halves of the cross form one straight line and the left and right halves line up.
   - Grid wall: Look straight ahead and adjust k1 until the lines are straight. Look at the edges and adjust k2. Finally, turn your head left and right. The grid should look pinned to a wall.
     If it follows your head or slides the opposite way, adjust "Screen to lens".
   - If you get lost, press "Cardboard defaults" to return to the starting point (nothing is written until you press "Save").
5. Only when a specific video was shot crooked do you need **Settings… › Video calibration**:
   Look at the subject and select "Set forward" to set both yaw and pitch at once. If the horizon is tilted, adjust "Horizon".
   "Eye vertical offset", "Convergence", and eye swap ("Eyes") are under "Advanced…". Always finish lens calibration before this step.

## Controls

The app UI follows the phone's system language: Traditional Chinese when the system language is Traditional Chinese (Taiwan, Hong Kong, Macau), English otherwise (including Simplified Chinese).

The menu is a panel fixed in space. On calibration pages the panel sits below your line of sight, so it does not cover the test pattern in the center.

There are two button layouts. Switch them on the home screen under "Controller test". The default is "Auto": controllers whose name contains MOCUTE use the one-hand layout; all others use the standard layout.
If another one-hand controller is not detected automatically (pressing the trigger shows both A and R2 on the test page), select "One-hand controller" manually.
The controller test page shows every signal the controller sends and the action the app maps it to, in real time. This page captures all buttons; to leave, press "Back" at the top left.

**Standard gamepad** (two-hand gamepads, remotes, keyboards)

| Button | Action |
|---|---|
| D-pad / left stick / arrow keys | Menu open: move focus; on value items, left/right adjusts the value directly (hold to accelerate). Menu closed: left/right = rewind / fast-forward, up/down = previous / next video |
| A / Enter / D-pad center | Select |
| B / Esc / system Back | Back |
| Start / Y / Menu | Open/close menu |
| X / Select / left stick press | Recenter |
| L1 / R1, previous / next track, rewind / fast-forward keys | Rewind / fast-forward 10 seconds |
| Play/pause key / play key / pause key / Space / R2 | Play / pause |

**One-hand controller** (for example, MOCUTE-052: top row B, SELECT; diamond below with X at 12 o'clock, Y at 3, R1 at 6, START at 9; trigger on the back)

| Button | Action |
|---|---|
| Joystick | Same as the standard gamepad's arrow keys: with the menu closed, left/right = rewind / fast-forward, up/down = previous / next video |
| Trigger, R1 (6 o'clock) | Menu open = select; menu closed = play / pause |
| X (12 o'clock) | Open/close menu |
| START (9 o'clock) / Y (3 o'clock) | Volume − / Volume + |
| B | Back |
| SELECT | Recenter |

The volume keys are passed to the system. When you change the volume with controller buttons, the current volume appears in the menu's status line (the system volume panel is not visible in VR).

**Recenter mode** (switch it in the player menu under "Settings…"; it is remembered)

- Horizontal only (default): for watching seated or standing. Recenter resets only the left/right direction. The horizon always stays aligned with the real world.
- Full orientation: for watching lying down or on your side. Recenter also resets up/down and tilt, so the video appears upright in front of you. Recenter again after changing posture.

**With the menu closed**

- Rewind / fast-forward: a press jumps 10 seconds. Holding jumps about every 0.4 seconds, and each second held jumps farther (10 s → 30 s → 1 min → 2 min → 5 min).
  For responsiveness, jumps land on the nearest keyframe, so the position may be off by a second or two.
- Previous / next: follows the order on the video list screen (including sort and filters). Holding does not switch repeatedly.
  If the system kills the app in the background and you return to the player, the list is gone and only the current video can play.

**Seek bar** (the row below the play button in the main menu)

- With focus on the seek bar, each left/right press jumps 10 seconds. Holding jumps farther and farther (same as with the menu closed).
- Look at a position on the seek bar (a yellow line appears) and press confirm to jump there.

**Playback state**

- The play button changes with the state: "Play", "Pause", "Replay" (at the end), or "Retry" (on error).
- At the end or on error, the menu opens automatically and shows the cause of the error on the panel.
- Resume: each video remembers where you left off (shared by all resolution variants of the same video). Opening it or switching to it resumes from there,
  and the status line shows "Resuming at 12:34". Positions under 5 seconds are not saved. Reaching the last 30 seconds counts as finished, and the next play starts from the beginning. To start over, jump to the far left of the seek bar.

**Calibration pages**

- B or "Cancel" discards the changes. Only "Save" writes them.
- The menu button only hides the panel and keeps the changes, so you can see the test pattern clearly. Pressing menu, confirm, or Back again returns to the same page.
- During lens calibration, the rewind / fast-forward buttons switch to the previous / next test pattern (also while the panel is hidden). While the panel is hidden, left/right on the D-pad also switches patterns.

Bluetooth gamepads, D-pad remotes, media remotes, and keyboards all work. Pair them in system settings first. The app needs no Bluetooth permission.

**Without a controller** (Cardboard button = screen tap)

- With the menu closed, tap once to open the main menu.
- Look at a menu item and tap to select it. For value items, look at − or + and tap.
- With the main menu open, look elsewhere and tap to close it. On calibration pages, tapping while looking elsewhere does nothing, to prevent accidental input.
- Long-press the screen to recenter.

## Video sources

### NAS (SMB)

On the home screen, enter the host (IP or name), share, user name, and password, then press "Connect and browse". A blank user name logs in as guest.
The connection details are saved as soon as you press connect, so you do not need to retype them if the connection fails.

### Local folders

"Add local folder" on the home screen opens the system folder picker. Phone storage, SD cards, and USB drives all work.
After you grant access once, the app remembers it. The app can access only the selected folders, needs no storage permission, and needs no network for playback.
If a folder loses its access permission, remove it and add it again.

Local playback is also useful for troubleshooting: if a video plays smoothly from local storage but stutters from the NAS, the bottleneck is network transfer.
If it stutters in both, the cause is decoding or overheating.

## Video list

- Sort: name, date, size. Tap the selected sort again to toggle ascending/descending (↑ / ↓). When you change the sort, name defaults to A→Z, and date and size default to newest and largest first.
- Include subfolders: lists videos up to 3 levels down, each labeled with its folder. Unreadable subfolders are skipped with a notice.
  When enabled, the list shows no folders, only videos.
- Hide under 100 MB (on by default): filters out small files such as previews and clips. Files whose size the source does not report are not hidden.
- These three options are remembered and shared by NAS and local folders.
- The system Back key first goes up one folder level; at the top level it returns to the home screen.

## File conventions

Same for NAS and local folders.

- Format: equirectangular side-by-side VR180, with the left half for the left eye. Fisheye output straight from the camera must be converted first.
- Extensions: `mp4`, `mkv`, `mov`, `m4v`, `ts`, `webm` (case-insensitive). Files and folders whose names start with `.` are not listed.
- Multiple resolution variants: add a tag at the end of the file name. They are grouped into one video automatically, and playback picks the highest variant the phone can hardware-decode:
  `trip_8K.mp4`, `trip_5.7K.mp4`, `trip_4K.mp4` (8K / 6K / 5.7K / 4K supported).
  - Separate the tag with `_`, `-`, `.`, or a space. Any case works.
  - Grouping happens only within the same folder. A file with the same name and no tag (for example, `trip.mp4`) is also included, labeled "Original", and listed first (usually the highest-quality master; if it cannot be decoded, a tagged variant plays instead).
  - Whether a variant can be hardware-decoded is based on the capabilities the decoder declares. These are often more conservative than reality, so the lowest (or only) variant is tried even if it exceeds them.
    An error is shown only if it really cannot be decoded.
- Scene calibration is stored next to the video in `trip.vr180.json` (shared by all variants and portable across phones). A copy is also kept on the phone.
  Playback reads the file next to the video first. If the folder is read-only, it is saved only on the phone. For the format, see [docs/data-formats.md](docs/data-formats.md#scene-calibration-file).

### Make a 5.7K phone version (ffmpeg)

The effective resolution of a phone in Cardboard is around 5.7K. The extra detail in 8K is barely visible, yet 8K is the most likely to exceed hardware decoding limits.

```bash
# CPU (best quality)
ffmpeg -i trip_8K.mp4 -vf scale=5760:2880:flags=lanczos \
  -c:v libx265 -crf 20 -preset slow -pix_fmt yuv420p10le -tag:v hvc1 \
  -c:a copy trip_5.7K.mp4

# NVIDIA GPU (much faster)
ffmpeg -hwaccel cuda -i trip_8K.mp4 -vf scale=5760:2880:flags=lanczos \
  -c:v hevc_nvenc -preset p5 -cq 22 -pix_fmt p010le -tag:v hvc1 \
  -c:a copy trip_5.7K.mp4
```

## Test video

`tools/make_test_video.sh` generates a 10-second 4K test video (blue background for the left eye, red for the right, a yellow line marking straight ahead,
and labels at 60° up, down, left, and right). Use it to check that the eyes are not swapped, the orientation is correct, and the image stays fixed when you turn your head. It requires ffmpeg. For details, see
[docs/development.md](docs/development.md#test-tools).
A ready-made file is attached to GitHub Releases (`vr180_test_4K.mp4`).
The test video is under 100 MB, so the video list hides it by default. Turn off "Hide under 100 MB" to see it.

## Known limitations

- The SMB password is stored in plain text in the app's private directory (other apps cannot read it, but a rooted phone can).
- The SMB domain cannot be set.
- Only side-by-side is supported. Top-bottom or fisheye would need to be added.
- The UI panel and the reticle are at the same position in both eyes (at infinity), so they may conflict in depth with nearby content.
- Head tracking is rotation only (3DoF). Moving forward or backward is not supported.
