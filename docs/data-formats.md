# Data formats

Every file the app writes: settings files in the app's private directory, and the scene calibration file (sidecar) next to each video.
These formats are deployed on users' phones and NASes. Any change to field names, types, or defaults must stay compatible with old files.

Code sources: `model/Profiles.kt` (fields and defaults), `data/ProfileStore.kt` (file names, read/write rules),
`source/Sidecar.kt` and `source/FileSource.kt` (sidecar location and read/write).

## Common rules

### Location

- Settings files are in `filesDir/vr180/` (that is, `/data/data/com.example.vr180/files/vr180/`).
  The phone copy of each scene calibration is in `scenes/` under it. Both folders are created automatically when `ProfileStore` is created.
- `AndroidManifest.xml` sets `allowBackup="false"`: these files are not included in Android backup and are deleted on uninstall.
- For `*.vr180.json` next to videos, see [Scene calibration file](#scene-calibration-file).

### Writing

- Content is `JSONObject.toString(2)`: UTF-8, 2-space indent.
- Every file (top-level settings files and files under `scenes/`) is first written to `<file name>.tmp` in the same directory and then `renameTo` the final name,
  so a power loss mid-write does not corrupt the file. The result of `renameTo` is not checked: if the rename fails, the final file keeps its old content and the `.tmp` stays in place.
- Each write rewrites the whole file with only the fields listed in the tables below. Any other fields in the file disappear on the next write.
- `Float` fields are written with `toDouble()`, so values may carry floating-point tails (for example, `0.30000001192092896`).

### Reading

- If the file does not exist, or its content is not a valid JSON object: the whole file is treated as missing and all fields use defaults.
- Missing field: uses that field's default (see the tables below).
- Types: reads use Android `org.json`'s `optDouble`/`optBoolean`/`optString`.
  Numeric strings are converted to numbers, `"true"`/`"false"` (case-insensitive) are converted to booleans, and numbers read as strings are converted to text.
  If conversion fails, the default is used. For a JSON `null` value, number and boolean fields use the default, and string fields read as `"null"`.
- Enum values (`alignment`, `choice`, `sort`) must match the enum name exactly (case-sensitive); otherwise the default is used.
- Value ranges are not checked: any number in the file is used as is. Ranges are enforced only when adjusting in the app UI.
- Exception: if an element of the `profiles` array in `lenses.json` or the `folders` array in `folders.json` is not a JSON object,
  reading throws `JSONException`, and this exception is not caught.

## device.json

Calibration of the phone itself (`DeviceCalib`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `pxPerMm` | number | `0` | Screen pixels per millimeter. `≤ 0` means uncalibrated; the system-reported density `(xdpi + ydpi) / 2 / 25.4` is used instead |
| `yawDriftDegPerSec` | number | `0` | Yaw drift rate measured at rest (degrees/second), cancelled linearly during playback. `0` shows as "Drift not checked yet" on the home screen |
| `predictionMs` | number | `25` | How far ahead to predict head orientation (milliseconds) |
| `fullRecenter` | boolean | `false` | Recenter mode: `false` horizontal only, `true` full orientation |

When written:

- "Save" in screen size calibration: changes only `pxPerMm`; the value is system density × slider factor (0.85–1.15).
- A completed gyro check with a valid result: changes only `yawDriftDegPerSec`.
- Releasing the motion prediction slider: changes only `predictionMs` (UI range 0–60).
- Switching recenter mode in the player's "Settings…": changes only `fullRecenter`; takes effect immediately.

The player reads it once at playback start and does not reread it during playback (switching recenter mode in the player also updates the in-memory value).

## lenses.json

List of viewer (lens) profiles and the active profile.

```json
{
  "activeId": "preset-cardboard-v1",
  "profiles": [ { "id": "…", "name": "…", "interLensMm": 60.0, … } ]
}
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `activeId` | string | `"preset-cardboard-v1"` | id of the active profile; can be a preset id |
| `profiles` | array of object | `[]` | User profiles, format in the table below; presets are not included |

Read rules:

- Presets always use the in-code values and are listed first. Elements of `profiles` whose `id` starts with `preset-` are ignored.
- If `activeId` is missing, an empty string, or matches no id in the list: `preset-cardboard-v1` is used.
- Only profiles whose `id` does not start with `preset-` are written, so presets never appear in the file.

When written:

- "Use Cardboard defaults" on the home screen, or selecting a profile or "Use Cardboard defaults" on the viewer profiles page: changes only `activeId`.
- "Delete" on the viewer profiles page: removes that profile (the active profile cannot be deleted).
- "Save" in VR lens calibration: if the active profile is a preset, adds a new entry with a new random UUID and the name "My viewer (based on <preset name>)";
  otherwise overwrites the entry with the same id. In both cases `activeId` is set to it.

### Lens profile (LensProfile)

All distances are in millimeters.

| Field | Type | Default on read | Range in VR calibration page | Meaning |
|---|---|---|---|---|
| `id` | string | A new random UUID on every read | — | Identifier. The `preset-` prefix is reserved for presets |
| `name` | string | `"未命名"` (fixed Chinese text, not localized) | — | Display name |
| `interLensMm` | number | `63` | 45–80 | Distance between lens centers ("Lens separation") |
| `screenToLensMm` | number | `40` | 20–70 | Screen-to-lens distance; sets the image scale |
| `lensHeightMm` | number | `31` | See below | Vertical position of the lens centers; meaning depends on `alignment` |
| `alignment` | string | `"BOTTOM"` | Cycles through all three | `"BOTTOM"`, `"CENTER"`, `"TOP"` |
| `k1` | number | `0.3` | −0.5–1.5 | Radial distortion coefficient |
| `k2` | number | `0.2` | −0.5–1.5 | Radial distortion coefficient |
| `halfFovDeg` | number | `50` | 30–75 | Per-eye half field of view (degrees); everything beyond is painted black |
| `offsetXMm` | number | `0` | −30–30 | Shifts both eyes' images left/right together; positive = right |
| `tiltDeg` | number | `0` | −10–10 | Angle of the line between lens centers relative to screen horizontal (degrees); positive = right eye higher |

- `lensHeightMm`: for `BOTTOM`, the distance from the lens center to the bottom edge of the screen; for `TOP`, to the top edge (the UI limits both to ≥ 0).
  For `CENTER`, the vertical offset from the screen center, positive = up (the UI limits it to −30–30).
- `k1` and `k2` follow the Cardboard definition, with radius in tan-angle units. For the formula, see [architecture.md](architecture.md#rendering-pipeline).
- A profile with no `id` gets a different id on every read, so `activeId` cannot match it; if it is active, the app falls back to Cardboard defaults.
- The defaults on read are fallbacks for missing fields and differ from every preset.

### Presets

Defined in `LensProfile.presets()` and not saved. `offsetXMm` and `tiltDeg` are both `0`.

| `id` | `name` | `interLensMm` | `screenToLensMm` | `lensHeightMm` | `alignment` | `k1` | `k2` | `halfFovDeg` |
|---|---|---|---|---|---|---|---|---|
| `preset-cardboard-v1` | Cardboard defaults | 60 | 42 | 31 | `BOTTOM` | 0.441 | 0.156 | 40 |
| `preset-cardboard-v2` | Cardboard v2 (approx.) | 63.9 | 39.3 | 31 | `BOTTOM` | 0.34 | 0.55 | 50 |
| `preset-generic-center` | Generic (screen-centered) | 63 | 40 | 0 | `CENTER` | 0.30 | 0.20 | 50 |

## connection.json

SMB connection details for the NAS (`SmbConnection`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `host` | string | `""` | Host IP or name |
| `share` | string | `""` | Share name |
| `user` | string | `""` | User name; blank (including whitespace only) logs in as guest |
| `password` | string | `""` | Password, in **plain text** |
| `domain` | string | `""` | Domain |

- Written when "Connect and browse" is pressed on the home screen, before connecting (kept even if the connection fails).
- The home screen has no domain field, so `domain` is always written as `""`. A domain written into the file by hand is cleared on the next connection.
- `host` and `share` are trimmed on input; `user` and `password` are stored as is.

## folders.json

Local folders the user has granted access to.

```json
{ "folders": [ { "uri": "content://…/tree/…", "name": "Movies" } ] }
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `folders` | array of object | `[]` | In the order added |
| `folders[].uri` | string | `""` | SAF tree URI (the string of the `OpenDocumentTree` result) |
| `folders[].name` | string | `""` | Display name, read from the document provider when added; if unavailable, the last URI segment or "Local folder" |

- Adding takes a persistable permission (read/write first, falling back to read-only). Adding the same `uri` again moves it to the end of the list.
- "Remove" releases the persistable permission and deletes it from the list.
- The file stores only the URI; the system keeps the permission itself. If the permission is revoked, browsing shows an error; remove the folder and add it again.

## controller.json

Controller button layout choice (`ControllerChoice`).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `choice` | string | `"AUTO"` | `"AUTO"`, `"STANDARD"`, `"ONE_HAND"`; other values are treated as `"AUTO"` |

Written when switched on the controller test page. For how `AUTO` decides and the button mapping of each layout, see [the README's "Controls"](../README.md#controls).

## browse.json

Video list display options, shared by NAS and local folders.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `sort` | string | `"NAME"` | `"NAME"`, `"DATE"`, `"SIZE"`; other values are treated as `"NAME"` |
| `descending` | boolean | `false` for `NAME`, `true` for `DATE` and `SIZE` | Whether to sort in reverse; when missing, the default direction for `sort` is used |
| `includeSubfolders` | boolean | `false` | Whether to list videos in subfolders |
| `hideSmall` | boolean | `true` | Whether to hide videos under 100 MB |

Toggling any option writes all four fields. For option behavior, see [the README's "Video list"](../README.md#video-list).

## positions.json

Last playback position of each video (for resume).

| Field | Type | Meaning |
|---|---|---|
| `items` | array | Most recently played first, at most 200 entries |
| `items[].key` | string | SHA-1 (lowercase hex) of the video key. Key rules are the same as [scenes/](#scenes); all variants of a video share it |
| `items[].posMs` | number | Playback position (milliseconds) |
| `items[].durMs` | number | Duration (milliseconds), `-1` when unknown |

When written: before switching to the previous/next video, when the player goes to the background or closes, and at the end. Not written when the player has not started playing or is in an error state.
If the position is under 5 seconds, or the duration is known and the position is within the last 30 seconds, the video's record is deleted instead of written (`worthResuming` in
`player/PlayerMath.kt`). A missing or malformed file is treated as having no records.

## scenes/

Phone copies of each video's scene calibration. The content format is exactly the same as the [Scene calibration file](#scene-calibration-file).

- File name is `<SHA-1>.json`: the key encoded as UTF-8 and hashed with SHA-1, written as 40 lowercase hex characters.
- Key (`SidecarRef.key`):
  - NAS: the SMB URI string of the scene calibration file, for example `smb://nas/video/trip/trip.vr180.json`.
    The URI is built by `Uri.Builder`, and the path is percent-encoded. The host is the string entered in the connection settings,
    so connecting to the same NAS by IP and by name gives different keys.
  - Local folder: `local:<tree URI>|<documentId of the containing folder>|<video base name>.vr180.json`.
  - When the player receives no scene calibration file location (the current browse screen always provides one), the playback URI of the highest-resolution variant is used instead.
- Written every time "Save" is pressed in VR; never deleted automatically.

## Scene calibration file

A video's scene calibration (`SceneCalib`), stored in the video's folder and shared by all resolution variants.

### Location and file name

`<base name>.vr180.json`. The base name is the video file name without the extension and without the resolution tag.
For example, `trip_8K.mp4`, `trip_4K.mp4`, and `trip.mp4` all map to `trip.vr180.json`.
For resolution tag rules, see [the README's "File conventions"](../README.md#file-conventions).

### Fields

| Field | Type | Default | Step in VR calibration page | Meaning |
|---|---|---|---|---|
| `version` | number | — | — | Always written as `1`; ignored on read |
| `yawDeg` | number | `0` | 1 | Horizontal direction of the video's straight-ahead (degrees) |
| `pitchDeg` | number | `0` | 0.5 | Camera pitch when shooting (degrees) |
| `rollDeg` | number | `0` | 0.2 | Horizon tilt (degrees) |
| `eyeVerticalDeg` | number | `0` | 0.05 | Vertical offset between left and right eyes (degrees), split half per eye |
| `convergenceDeg` | number | `0` | 0.1 | Horizontal shift between left and right eyes (degrees), split half per eye |
| `swapEyes` | boolean | `false` | — | When `true`, the left eye samples the right half and the right eye the left half |

- Angle fields wrap around to −180–180 when adjusted in the VR calibration page. "Set forward" computes `yawDeg` and `pitchDeg` directly.
- For how angles are applied to the image, see [architecture.md](architecture.md#rendering-pipeline).

### Read order

Read in the background at playback start. The first one that succeeds is used:

1. The scene calibration file next to the video: exists, is readable, and is a valid JSON object.
2. The phone copy in `scenes/`.
3. All defaults.

Any error while reading the scene calibration file (network, permission, format) is treated as the file not existing.
The file next to the video takes priority: even if the phone copy is newer (for example, the folder later became read-only), the one next to the video is used.

### Writing

When "Save" is pressed in VR "Video calibration", these run in the background in order:

1. Write the phone copy in `scenes/` (always written).
2. Write the scene calibration file next to the video:
   - NAS: open with `FILE_OVERWRITE_IF`; overwrite if it exists, create it if not.
   - Local folder: overwrite the file with the same display name in the containing folder (`"wt"` mode); if none is found, create a new file with MIME type
     `application/json`.
3. The result is shown in the menu status line: on success, "Video calibration saved on the phone and in the video folder"; if step 2 fails (read-only share,
   no write permission, etc.), "Can't write to the video folder; calibration saved on the phone only".
