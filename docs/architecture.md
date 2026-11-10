# Architecture

Component roles, threading model, playback data flow, and how rendering and input work. All code is in
`app/src/main/java/com/example/vr180/`; paths below are relative to it.
For settings and scene calibration file formats, see [data-formats.md](data-formats.md). For button mapping, see [the README's "Controls"](../README.md#controls).

## Components and responsibilities

```
NAS ──SMB──▶ SmbDataSource (2 MiB block reads) ──┐
Local folder ──▶ ContentDataSource ──────────────┤ RoutingDataSource (chosen by URI scheme)
                                                 ▼
           ExoPlayer (hardware video decoders only; auto downgrade when over capability)
               │  Surface
               ▼
           SurfaceTexture / OES external texture (zero copy)
               │
HeadTracker ──▶ VrRenderer shader: screen pixel → lens distortion correction → view direction
(prediction, drift compensation)  → head rotation → scene calibration → VR180 sampling / calibration pattern / menu panel
```

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Compose screens for settings and browsing (switched by the `Screen` enum, no navigation library); captures all input events while the controller test page is open |
| `PlayerActivity.kt` | VR playback screen: creates ExoPlayer and GLSurfaceView, input dispatch, VR menu pages, scene calibration read/write |
| `model/Profiles.kt` | `LensProfile`, `DeviceCalib`, `SceneCalib`, `SmbConnection`, and their JSON conversion |
| `data/ProfileStore.kt` | Reads and writes all settings files in the app's private directory |
| `source/FileSource.kt` | Video source interface `FileSource`, implemented by `SmbFileSource` and `LocalFileSource` (SAF) |
| `source/Sidecar.kt` | Scene calibration file location `SidecarRef` and read/write `SidecarIo` |
| `smb/SmbRepository.kt` | SMB connection to a single NAS (smbj): list directories, open files, read/write small files, build `smb://` URIs |
| `smb/SmbDataSource.kt` | ExoPlayer data source for SMB |
| `smb/LocalNetworkPermission.kt` | Runtime permission check for `ACCESS_LOCAL_NETWORK` from Android 17 |
| `player/RoutingDataSource.kt` | Sends `smb://` to `SmbDataSource` and `content://` to `ContentDataSource`; other schemes throw `IOException` |
| `player/HardwareOnly.kt` | `MediaCodecSelector`: keeps only hardware-accelerated video decoders; audio is not filtered |
| `tracking/HeadTracker.kt` | Head tracking: prediction, recentering, drift compensation |
| `tracking/DriftMeter.kt` | Stationary drift measurement for the gyro check page |
| `render/VrRenderer.kt` | `GLSurfaceView.Renderer`: computes lens centers and matrices each frame, draws once per eye |
| `render/Shaders.kt` | Vertex and fragment shaders for the single draw pass |
| `render/M3.kt` | 3×3 rotation matrix utilities (row-major) |
| `vrui/VrMenu.kt` | World-fixed VR menu panel: layout, gaze hit testing, focus, drawing to a Bitmap |
| `input/ControllerInput.kt` | Key and joystick events → `VrAction`; handles key repeat and one-hand trigger dedupe |
| `input/ControllerProfile.kt` | Button layouts (standard / one-hand) and `ControllerChoice` (auto detection) |
| `input/InputLog.kt` | Event log for the controller test page |
| `ui/` | Compose screens: home, browse, screen size calibration, gyro check, viewer profiles, controller test; `Errors.kt` turns exceptions into user-facing messages |

## Threading model

### PlayerActivity

| Thread | Responsibility |
|---|---|
| Main thread | Activity lifecycle, input events, all ExoPlayer calls and callbacks, playback time update every 500 ms (`clock`), sensor events (`registerListener` with no Handler) |
| GL thread | `VrRenderer.onDrawFrame`, all `VrMenu` state, actions of menu pages, live lens and scene calibration values |
| `io` (single-thread Executor) | Scene calibration reads and saves, lens profile saves |
| ExoPlayer loader thread | Calls `RoutingDataSource`/`SmbDataSource` to read files |

Cross-thread rules:

- Main thread → GL thread: `glView.queueEvent { … }`. GL thread → main thread: `runOnUiThread { … }`.
  All controller actions except `PLAY_PAUSE` go to the GL thread first.
- Render parameters (`lens`, `scene`, `mode`, `guide`, `hasVideo`, `pxPerMm`) are `@Volatile` immutable values, replaced whole, never modified in place.
- Playback state (`playing`, `posMs`, `message`, etc.) is `@Volatile`, written by the main thread and read by the GL thread to draw the menu.
  The main thread builds the `VrMenu.status` string, sets it, and requests a redraw with `queueEvent`.
- `HeadTracker` exchanges sensor data under an internal lock. `headMatrix()` is called on the GL thread every frame.

### Settings and browse screens

- Compose runs on the main thread. Directory listing and SMB connection tests run as coroutines on `Dispatchers.IO`.
- All `ProfileStore` methods are `@Synchronized`; both the main-thread screens and the player's `io` thread call them.
- `SmbRepository` is an app-wide singleton that keeps only one NAS connection. `configure()`, `share()`, and `closeAll()` are
  `@Synchronized`; listing and read/write run on the caller's background thread.

## Playback data flow

### From browser to player

1. `BrowserScreen` scans the directory (with subfolders included, breadth-first down to at most 3 levels) and collects only files with the extensions
   `mp4`, `mkv`, `mov`, `m4v`, `ts`, `webm`. Entries whose names start with `.` are excluded at listing time.
   An unreadable subfolder is skipped and counted. If the current level cannot be read, the whole list shows an error.
   Only changing folders, toggling "Include subfolders", or retrying rescans. Sort and "Hide under 100 MB" apply to the in-memory scan result.
   Changing folders or options during a scan cancels the old scan; it is not shown as a read failure.
2. Files with the same base name in the same folder are grouped into one video. Variants are sorted by resolution tag from high to low
   (untagged original > `8K` > `6K` > `5.7K` > `4K`). For the tag rules, see [the README's "File conventions"](../README.md#file-conventions).
3. Selecting a video starts `PlayerActivity` with an Intent (repeated selections within 1.5 seconds open it only once):

   | Extra | Content |
   |---|---|
   | `uris` | Playback URIs of each variant (`smb://host/share/path` or SAF document URI), high to low |
   | `labels` | Labels of each variant (`8K`…; the untagged one is "Original") |
   | `sidecar` | Scene calibration file location, the JSON string from `SidecarRef.encode()` (`kind` is `smb` or `local`) |
   | `title` | Base name of the video |
   | `playlistIndex` | Position of this video in `Playlist.items` |

   At the same time, the whole video list on screen (in the current sort and filter order) is put in the in-memory `player/Playlist`, so the player can switch to previous/next.
   The list is not put in the Intent (it can hold hundreds of videos, and Intents have a size limit). After the process is killed the list is empty; when `playlistIndex` does not match the current
   `uris`, the player treats it as having no list. Switching resets the variant, error state, and title, and reloads that video's scene calibration.

   If `uris` is empty, the player finishes immediately.

### Player

- `DefaultRenderersFactory` uses `HardwareOnly.codecSelector` and enables `setEnableDecoderFallback(true)`: when a decoder fails to initialize, it tries
  a lower-priority decoder. The candidate list comes from the selector and contains only hardware decoders, so it never falls back to software decoding.
- `DefaultLoadControl`: time bounds are at least 15 seconds and at most 40 seconds. Playback starts after 1 second of buffer at start and after a seek, and after 5 seconds when it stalls mid-playback.
  `targetBufferBytes` is not set, so the buffer is also bounded by Media3's default byte limit (about 144 MB for video plus audio):
  above about 77 Mbps, buffering stops before 15 seconds (about 10 seconds at 120 Mbps). It is not raised because the buffer lives on the Java heap,
  and 40 seconds of 8K would exceed the heap limit.
- Seeking uses `SeekParameters.CLOSEST_SYNC`: it lands on the nearest keyframe instead of decoding from the previous keyframe to the exact position.
  8K HEVC often has keyframe intervals of several seconds, and exact seeking shows almost no picture while seeking repeatedly on hold.
- Relative seeks (rewind/fast-forward, left/right keys) count from the previous target position while held, not from `currentPosition`,
  so snapping to a keyframe does not jump back to the same position.
- When switching videos or downgrading, the previous frame stays until the new video's first frame: the renderer pauses `updateTexImage` (`holdVideoFrame`),
  so the texture stays on the last frame of the previous video; `onRenderedFirstFrame` releases it. Between closing the old decoder and the new video's first frame,
  some decoders output blank frames; not consuming them avoids a black flash. Opening a new file from a NAS requires connecting and buffering, which can take several seconds.
  Meanwhile, the image dims smoothly to 35% within about 0.25 seconds (`dimVideo`, the shader's `uVideoGain`) and recovers about 0.4 seconds after the new video's first frame,
  so a large brightness difference between two videos is not jarring.
- `ProgressiveMediaSource` uses `RoutingDataSource` and deliberately not `DefaultDataSource`, so the player can read only SMB and SAF.
- `SmbDataSource` reads 2 MiB from the NAS into a buffer at a time and hands it to ExoPlayer in pieces, so requests of a few tens of KB are not dragged down by network latency.
  The smbj per-read limit is negotiated with the NAS; if it is under 2 MiB, reading continues. The block is not made larger: after a seek, a full block must be read before the first byte can be returned.
- When opening an smb file, smbj errors are classified by NT status code: `STATUS_OBJECT_NAME_NOT_FOUND`/`STATUS_OBJECT_PATH_NOT_FOUND`
  → `IO_FILE_NOT_FOUND`, `STATUS_ACCESS_DENIED`/`STATUS_LOGON_FAILURE` → `IO_NO_PERMISSION`,
  others → `IO_NETWORK_CONNECTION_FAILED`. The player shows the cause based on the error code.
- When the player is created and there is an `smb://` URI, it configures `SmbRepository` from `connection.json`: after the system kills the process and
  the user returns straight to the player, the singleton is empty.
- `SmbRepository` smbj settings: multi-protocol negotiation, 8 MiB read buffer, 30-second timeout, full BouncyCastle.
  Before connecting, it tries TCP 445 with a 6-second timeout, so a wrong address does not wait 30 seconds. A blank user name logs in as guest.

### Starting playback

After the player opens, it calls `prepare()` but does not play; the prompt panel follows the gaze. The first action (excluding repeats) triggers the start:
the Back action finishes the player; any other action recenters and starts playback.

When opening a video or switching to previous/next, if `positions.json` has a record for this video, it first calls `seekTo` to that position (resume).
The current position is written back before switching, in `onPause`, and at the end. For format and rules, see [data-formats.md](data-formats.md#positionsjson).

### Variant selection and downgrade

- `playVariant(i)` plays the i-th variant. Only the last variant sets `setExceedRendererCapabilitiesIfNecessary(true)`,
  so a track that exceeds the decoder's declared capabilities is still tried on the hardware decoder.
- When `onTracksChanged` finds the video track exceeds declared capabilities: downgrade if a lower variant exists; if it is already the last variant, only show a notice and keep playing.
- When `onPlayerError` is decoder-related (`DECODER_INIT_FAILED`, `DECODER_QUERY_FAILED`, `DECODING_FAILED`,
  `DECODING_FORMAT_EXCEEDS_CAPABILITIES`, `DECODING_FORMAT_UNSUPPORTED`): downgrade; if the last variant also fails, stop and show the error.
  Other errors (network, file not found, corrupt format, etc.) do not downgrade; they show the error and turn the play button into "Retry".
- A downgrade resumes from the previous playback position.
- Each variant downgrades at most once (`failedVariant`). "Retry" calls `prepare()` again on the current variant and seeks back to the previous position.
- At the end (`STATE_ENDED`) or on error, the main menu opens automatically if the menu is not open.

### Scene calibration

Read on the `io` thread at start. Saving writes the phone copy and the file next to the video. For read order, write method, and failure behavior, see
[data-formats.md's "Scene calibration file"](data-formats.md#scene-calibration-file). "Video calibration" cannot be entered until reading finishes.

## Head tracking

`HeadTracker` uses `TYPE_GAME_ROTATION_VECTOR` (`SENSOR_DELAY_FASTEST`): the system fusion already estimates gyro bias,
and pitch and roll are locked by gravity. The magnetometer is deliberately not used, at the cost of slow yaw drift.

- Angular velocity: the quaternion difference between two consecutive samples (used only when the interval is 0.1 ms–100 ms) is converted to angular velocity in device coordinates,
  smoothed with `ω = 0.6·ω + 0.4·ω_new`.
- Prediction: `headMatrix(now)` extrapolates the orientation by `min(now − last sample time, 50 ms) + predictionMs`. `now` uses
  `SystemClock.elapsedRealtimeNanos()`, the same time base as sensor timestamps.
- The head orientation is read twice per frame: the first read is for menu gaze hit testing (which may then redraw and upload the texture); the second is read after that and used to draw the frame.
- Coordinates: axes are remapped according to the screen rotation at Activity creation (`ROTATION_90`/`ROTATION_270`), then the Android world
  (east, north, up) is converted to the GL world (x = east, y = up, z = south; the eye looks along −z).
- Recenter: on the next frame, the current yaw is recorded as `yaw0`, the full orientation as `ref`, and the drift timer origin `tRef` is reset.
- Output (`tracking/Recenter.kt`, `drift = yawDriftDegPerSec·(now − tRef)`):
  - Horizontal only: `rotY(yaw0 + drift) · G`. Pitch and tilt are locked by gravity, and the horizon stays aligned with the real world.
  - Full orientation (`fullRecenter`): `refᵀ · rotY(drift) · G`. The orientation at recenter becomes straight ahead, so the image is upright even lying down or on your side;
    the horizon is no longer aligned with gravity, so recenter again after changing posture.

`DriftMeter` (gyro check page): after start, it waits 1.5 seconds, samples for 15 seconds, and fits the slope of "yaw vs. time" by least squares.
If the cosine of the screen normal's tilt changes by more than 0.02, the phone is considered moved; fewer than 50 samples is considered insufficient. Neither case is saved.

## Rendering pipeline

`GLSurfaceView` (OpenGL ES 3.0, continuous rendering, `preserveEGLContextOnPause`), a single shader program,
and one full-screen quad. Each frame draws once per eye with no intermediate framebuffer, so each video texture pixel is sampled only once.

### Per frame (VrRenderer.onDrawFrame)

1. When a new frame is available, call `updateTexImage()` and get the texture transform matrix.
2. Get the head matrix `H` (world ← screen) and call `frameHook` (menu gaze hit testing and redraw; see the next section).
3. Compute lens centers (window pixel coordinates; `W` and `Hpx` are the full window width and height, `p` is `pxPerMm`):
   - Vertical reference `cy`: `BOTTOM` = `lensHeightMm·p`; `TOP` = `Hpx − lensHeightMm·p`; `CENTER` = `Hpx/2 + lensHeightMm·p`.
     When lens calibration switches the reference, `lensHeightMm` is converted using the screen height (`Hpx / p`), so the physical lens center position does not change (`changeLensAlignment` in `player/PlayerMath.kt`).
   - Half distance between eyes `s = interLensMm/2·p`, tilt angle `θ = tiltDeg`, `side` −1 for the left eye and +1 for the right:
     `cx = W/2 + offsetXMm·p + side·s·cosθ`, `cy_eye = cy + side·s·sinθ`.
     Phone tilt only moves the lens centers and does not rotate the image (the gyro measures the phone's orientation).
   - `pxPerMm`: use the calibrated value in `device.json` if it exists (> 0); otherwise use the system density `(xdpi + ydpi) / 2 / 25.4`.
4. Scene rotation `Rs = rotY(yawDeg)·rotX(pitchDeg)·rotZ(rollDeg)`. Each eye is further multiplied by a per-eye correction
   `A = rotY(±convergenceDeg/2)·rotX(±eyeVerticalDeg/2)` (+ for the left eye, − for the right),
   `contentFromEye = A · Rsᵀ · H`.
5. Each eye's viewport is half of the screen. When `swapEyes` is `false`, the left eye samples the left half of the video and the right eye the right half; when `true`, they are swapped.

### Fragment shader (Shaders.FRAGMENT)

1. `t = (gl_FragCoord.xy − lens center) / (screenToLensMm·p)`: tan angle relative to the lens center.
2. Distortion correction (same as Cardboard SDK): `te = t·(1 + k1·r² + k2·r⁴)`, `r² = |t|²`.
3. Pixels with `max(|te.x|, |te.y|) > tan(halfFovDeg)` are painted black at the end.
4. View direction `eye = normalize(te.x, te.y, −1)`.
5. Color by `mode`:

   | mode | Name | Content | Follows head |
   |---|---|---|---|
   | 0 | VIDEO | `c = contentFromEye·eye`, `lon = atan(c.x, −c.z)`, `lat = asin(c.y)`; sample only when longitude is within ±90°, `uv = (offset + (lon/π + 0.5)·0.5, lat/π + 0.5)`, `offset` is 0 or 0.5, then multiplied by the texture transform matrix; when `guide` is on, a red horizon line is overlaid | World-fixed |
   | 1 | GRID (Grid wall) | Coarse and fine grids on the z = −1 plane straight ahead, a green vertical line straight ahead, a red horizon line | World-fixed |
   | 2 | NONIUS (IPD alignment) | The left eye draws only the top and left halves of the cross, the right eye only the bottom and right halves, plus a yellow reference circle shared by both eyes | World-fixed |
   | 3 | CENTER (Centering) | Concentric circles and a cross drawn in `te`, and a yellow frame at `tan(halfFov)·0.97` | Fixed to the screen |

6. When the menu is open: where the gaze ray meets the panel plane, the menu texture is sampled and blended with premultiplied alpha; a white reticle (black outline) is drawn at the lens center.

### VR menu panel (VrMenu)

- The panel is drawn on a 1200×1000 Bitmap (Android Canvas, which can use CJK fonts directly). It is redrawn and uploaded as a texture only when something changed and the panel is visible
  (with `texSubImage2D` when the size is unchanged, no reallocation). A gaze position change on the seek bar counts as a change only if it exceeds 1.5/200.
  Changes while hidden are drawn the next time the panel is shown. A GL context rebuild (new video Surface) requests one redraw.
- The panel is a world-fixed plane: center direction `f`, right `r`, up `u`, horizontal half-width `tan 27°`, kept upright (`r` lies in the horizontal plane).
  The main menu is placed in the gaze direction when opened; calibration pages are placed 36° below horizontal straight ahead, so the test pattern in the center is not covered.
- Each frame intersects the gaze with the panel to find the item under the gaze, the − / + zones of value items (22% of the width on each side), and the position on the seek bar (quantized to 1/200).
- Layout: title, up to 5 lines of status and help (status first; help is truncated when it overflows), then rows that split their width evenly.

## Input

### Event sources

- Keys: `PlayerActivity.onKeyDown/onKeyUp` → `ControllerInput`. The layout is resolved per event from the device name
  (`ControllerChoice.resolve`). Unmapped keycodes go back to the system (for example, volume keys).
- Joystick and D-pad axes: `onGenericMotionEvent`, reading only `AXIS_HAT_X/Y` and `AXIS_X/Y` (whichever has the larger absolute value).
  Beyond ±0.6 counts as pressed.
- Touch (Cardboard button): tap = `SELECT` (marked as from touch), long press = `RECENTER`.
- System Back (gesture or Back key) = `BACK`.

### ControllerInput

- Key repeat: only up/down/left/right repeat. After 400 ms held, an event is sent every 90 ms with the milliseconds held. System key repeat events are ignored.
- In the standard layout, A and R2 are two separate keys, sent on press with no waiting.
- In the one-hand layout, A, R1, and R2 are all `TRIGGER`. The one-hand controller's trigger sends A+R2 together (they cannot be separated); within 80 ms they count once, with no waiting.

### Action dispatch (PlayerActivity)

| Action | Behavior |
|---|---|
| `PLAY_PAUSE` | The main thread handles the play button directly: retry on failure, replay at the end, otherwise play/pause |
| `SEEK_BACK`/`SEEK_FWD` | On the lens calibration page, switch test patterns (cycles CENTER → NONIUS → GRID); otherwise jump ∓10 seconds |
| `VOL_UP`/`VOL_DOWN` | The main thread adjusts `STREAM_MUSIC` volume (no system panel) and writes the current volume to the status line |
| `TRIGGER` | Brings back the panel when hidden; same as `SELECT` when the menu is open or during "Set forward"; otherwise same as the play button |
| `RECENTER` | Recenters; when the menu is open, also moves the panel to the new straight-ahead direction |
| `MENU` | Brings back the panel when hidden; hides the panel when a calibration page is open (keeps changes; during "Set forward", first returns to the scene calibration page); closes the main menu when open; otherwise opens the main menu |
| Others | See below |

Other actions are checked in order:

1. When the panel is hidden, `SELECT`/`BACK` bring it back.
2. During "Set forward", `SELECT` (for touch, only when the gaze is not on any item) sets the current gaze as straight ahead.
3. Menu closed:
   - `SELECT`/`BACK` open the main menu.
   - Left/right: when the lens calibration panel is hidden, switch test patterns; otherwise seek. While held, at most one jump every 400 ms,
     with distance by hold time: < 1 s 10 s, < 2 s 30 s, < 3 s 1 min, < 4 s 2 min, then 5 min (`holdSeek`).
   - Up/down (initial press only; no action while the calibration panel is hidden): switch to the previous/next video in `Playlist`.
4. Touch with the gaze not on any item: closes the menu on the main menu; ignored on calibration pages (to avoid accidentally hitting buttons such as "Reset all").
5. Passed to `VrMenu.handle`. When holding left/right with focus on a value item, the steps per repeat depend on hold time:
   < 1 s 1 step, < 2 s 3 steps, < 3 s 6 steps, then 12 steps.

### VrMenu focus rules

- Up/down moves to the previous/next cell in reading order (when a row has two value items, only the right one is reachable).
- Left/right: value items adjust the value; the seek bar calls the same `holdSeek` as with the menu closed; buttons move focus within the row.
- Confirm: buttons run their action; value items adjust only when the gaze is on the − / + zone of the same cell; the seek bar jumps to the gaze position only when the gaze is on the seek bar.
- The gaze takes focus only when it moves to a "different" item, so it does not override the controller's selection.
- When the panel has just been placed in front of the gaze (open, bring back, recenter), the cell under the gaze is ignored until the gaze moves to another cell.
  When the main menu opens, the panel center lands exactly on "Settings…". This rule keeps "open and press confirm" acting on the play button,
  and keeps "look elsewhere and tap to close the menu" working as before.

### Controller test page

When `InputLog.active` is `true`, `MainActivity.dispatchKeyEvent`/`dispatchGenericMotionEvent` log and capture all events
(except the system Back key) and show the action the app maps each one to, according to the layout selected on the test page. In the one-hand layout, the second key of the same trigger press is marked "counted once".
