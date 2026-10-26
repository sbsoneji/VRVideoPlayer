# VR Video Player: developer guide

An Android app (Kotlin) that plays videos in any Cardboard-style viewer, the kind where you slide
your phone in. It uses Google's **Cardboard SDK** for head tracking and lens-distortion
correction, and **Media3 ExoPlayer** for playback.

| Video type | What you see |
|---|---|
| Normal 2D | A big virtual cinema screen in front of you |
| 3D side-by-side | Left half of the picture goes to your left eye, right half to your right eye |
| 3D top-and-bottom | Upper half to your left eye, lower half to your right eye |
| 360° | The video wrapped around you; look around by turning your head |

Videos come from a file on the phone (system file picker) or from a link (mp4, HLS `.m3u8`, DASH `.mpd`).

> **Status.** Version 1 (playback) was built and tested on a real phone. Versions 2 and 3
> (settings, captions, status bar, top-and-bottom 3D; then the in-headset menu, the dot with
> look-to-click and zoom up to 400%) were written afterwards in an environment without the
> Android build tools, so they have been checked by reading only. If the first build of this version
> fails, copy the first red error from Android Studio's Build panel.

## What you need

- A recent **Android Studio** (it must support Android Gradle Plugin 9.2.0; if Studio says the
  AGP version is too new, update Studio).
- From Android Studio's *SDK Manager*: **Android SDK Platform 37**, **NDK (Side by side)
  29.0.14206865** and **CMake 3.22.1** (*SDK Tools* tab). Studio normally offers to install
  missing pieces when it syncs the project.
- A **physical phone**, Android 8.0 or newer, with a gyroscope and OpenGL ES 3. The emulator
  has no head tracking.
- A Cardboard-style viewer.

## Build and run

1. Unzip, then in Android Studio choose **File > Open** and select the `VRVideoPlayer` folder.
2. Let Gradle sync and accept any prompts to install SDK / NDK / CMake pieces.
3. Connect your phone (USB debugging on) and press **Run**.
   The first build is slow: it compiles the Cardboard SDK's native code for two CPU types.

There is no `gradlew` script in this folder (see *Notes*). Android Studio downloads the right
Gradle version by itself from `gradle/wrapper/gradle-wrapper.properties`.

## Using it

1. Pick a video, or paste a link and press **Use link**.
2. Check the video type. It is guessed from the file name (`360` → 360°, `sbs`/`3d` → side-by-side), so correct it if needed.
3. Leave **Headset mode** on. Turn it off to try the video on the phone alone: you get one
   full-screen view that you can look around by moving the phone.
4. Press **Start**, put the phone into the headset, then press the viewer's button.

For the best picture, press **Set up my viewer** once and scan the QR code on your viewer
(usually on the lens plate or inside the box). If your viewer has no QR code, skip it: the
original Cardboard profile is used.

### The dot and the in-headset menu (no buttons needed)

A small dot sits in the middle of your view. Move your head so the dot is on a button, and
**keep it there for 2 seconds**: the button is clicked (a ring around the dot fills up while
you wait). With a mouse, click while the dot is on the button instead; that works at once.

- **Control bar**: look down to bring it up (it also stays up while the video is paused or has
  not started). Buttons: back / forward, Play / Pause, volume down / up, Center, Settings, Exit.
  Below them is a **seek bar**: look at (or click) any point on it to jump there. Back / forward and volume repeat while you keep looking at them.
- **Settings page** (the *Settings* button on the bar): zoom (50–400%), distance, height,
  brightness, contrast, eye distance (one step at a time, wide range), speed, captions on/off, caption size and position,
  subtitle track, audio track, and the look-click on/off and look time. Changes are saved.
- In **Settings > Pointer** on the phone you can hide the dot, switch looking-to-click off
  (mouse or tap still work), and change the look time (1–5 seconds).

**Mouse pointer.** With look-click turned **off**, a click lands where the mouse pointer is, not
at the dot (the pointer position is mapped to the menu approximately; the lens warping is not
taken into account, so it can be slightly off near the edges). With look-click on, a click
presses whatever the dot is on.

**Calibrating the mouse.** If clicks land beside the pointer, open the in-headset Settings page,
scroll to the bottom rows and press **Mouse pointer: Calibrate** (use look-click for this step).
Nine dots appear one after another; put the mouse pointer on each dot and click. The app then
measures your headset's lens warp and saves it. **Calibration: Reset** removes it.

### Other controls inside the headset

| Action | Result |
|---|---|
| Viewer button, first time | Centers the picture on where you are looking, starts the video |
| Viewer button | Play / pause (replays when the video has ended) |
| Viewer button twice, quickly | Recenter: moves the screen (or the front of a 360° video) to where you are looking |
| Volume up / down | Skip forward / back (the step is a setting, 10 seconds by default) |
| Earphone button, Bluetooth gamepad A / Enter | Same as the viewer's button |
| ✕ icon, top left of the screen | Leave the player (the phone's back button also works) |
| Gear icon, top right of the screen | Scan a viewer QR code |

The ✕ and gear icons can only be tapped with the phone in your hand, so use them before the
phone goes into the headset.

A status bar (play/pause symbol, time and progress) appears in the headset for a few seconds
after every action, and stays up while the video waits for the first button press.

**Viewer has no button that touches the screen?** Many cheap viewers don't. Turn on *Start
automatically* in Settings, or use an earphone / Bluetooth button as listed above.

Volume keys are used for skipping, so set the volume before you put the phone in the headset.

## Settings

Press **Settings** on the start screen. They are saved and used every time a video starts.

| Group | What you can change |
|---|---|
| Screen | Video zoom (size, 50–400%), screen distance, screen height, screen shape (16:9, 4:3, 21:9, 1:1, stretch) |
| Picture and brightness | Video brightness, contrast, colour saturation; phone screen brightness while playing |
| 3D | Half / full picture format, swap left and right eye |
| Headset | Which viewer profile is in use (tap to scan another), eye distance adjustment |
| Pointer | Show the dot, click by looking on/off, look time |
| Playback | Speed, skip step, repeat, start automatically (and its delay), preferred audio language |
| Captions | On/off, preferred language, size, colour, background darkness, position |

**Captions** come from subtitles inside the video, or from a subtitle file you add on the start
screen (.srt, .vtt, .ass/.ssa, .ttml). Captions stay at the bottom of the screen for flat video
and in front of your head for 360° video.

**Your viewer is remembered.** Scan its QR code once; the app keeps it in its private storage
and shows its name on the start screen. Until a code is scanned, the original Google Cardboard
profile is used. Uninstalling the app or clearing its data erases the saved viewer.

**Eye distance adjustment** moves the left and right pictures sideways, apart (+) or together
(−). It is an approximation for people whose eye distance differs from the viewer's lens
distance; the exact lens distance comes from the viewer's QR code.

**Focus (diopter) cannot be set by software.** Focus is decided by the physical lenses in the
viewer. The app can't make the picture sharper for short- or long-sighted eyes. Options are to
wear glasses inside the viewer if they fit, use a viewer with focus adjustment, or fit clip-in
prescription lenses.

## How it works

The Cardboard SDK is a C library, built from source rather than downloaded as a ready-made
dependency. This project therefore contains **unmodified copies of four folders** from the
upstream repository (`sdk/`, `java_api/`, `proto/`, `third_party/`) and builds them as the
`:sdk` Gradle module. `java_api/` is a Java layer over the native library that offers a
`CardboardView` and a simple `Renderer` interface, which is what the Kotlin code uses. This
is the same arrangement that upstream's own demo app ("paperscope", which also has a video
player) uses. Details and the exact upstream version are in `NOTICE.md`.

```
app/
  CMakeLists.txt         builds cardboard_sdk_jni, the small JNI bridge to the native SDK
  build.gradle           also extracts libGfxPluginCardboard.so from the SDK's .aar (extractNdk)
  src/main/java/com/example/vrvideoplayer/
    MainActivity.kt      start screen: pick video/subtitles, choose type, start
    SettingsActivity.kt  settings screen (list of settings: res/xml/preferences.xml)
    VrSettings.kt        reads the saved settings
    VrPlayerActivity.kt  VR screen: ExoPlayer, viewer-button controls, menu actions, lifecycle
    MenuModel.kt         the in-headset control bar and settings page (buttons, icons, drawing)
    AboutActivity.kt     About screen (texts and links: res/values/strings.xml, about_* entries)
    VideoRenderer.kt     OpenGL: video texture -> cinema screen or 360° sphere, per eye
    TextOverlay.kt       text/shape pictures drawn into the 3D scene (captions, status bar)
    ViewerInfo.kt        reads and scans the viewer profile
    VideoMode.kt         the video types
sdk/  java_api/  proto/  third_party/   Cardboard SDK, unmodified
```

Per frame, `CardboardView` gives the renderer the head orientation and, for each eye, a view and
projection matrix. `VideoRenderer` draws the video (an external OpenGL texture fed by
ExoPlayer) as a quad or a sphere. The SDK then applies the lens distortion for your viewer.

## Changing things

All in `VideoRenderer.kt` unless noted:

- **Default screen size**: `MAX_SCREEN_WIDTH`, `MAX_SCREEN_HEIGHT`, `REFERENCE_DISTANCE`
  (everything else is a setting).
- **360° sphere detail**: `SPHERE_LON_SEGMENTS`, `SPHERE_LAT_SEGMENTS`.
- **Side-by-side detection**: a frame at least 3× wider than tall is treated as *full* SBS
  (two complete pictures); anything narrower as *half* SBS (each picture squeezed to half
  width, stretched back on screen). See `FULL_SBS_MIN_ASPECT`.
- **Double-press time, status bar time**: constants in `VrPlayerActivity.kt`.
- **Add a setting**: add it to `res/xml/preferences.xml`, a key and field in `VrSettings.kt`,
  and use the field where it applies.
- **http:// links**: allowed by `usesCleartextTraffic` in `AndroidManifest.xml`; set it to
  `false` if you only use https.

## Troubleshooting

- **"... requires libraries and applications that depend on it to compile against version N
  or later"**: set `compileSdk = N` in `app/build.gradle` (and install that SDK Platform).
- **NDK / CMake not found**: install the exact versions listed above in the SDK Manager. If
  you would rather use other versions, change `ndkVersion` in `app/build.gradle` *and*
  `sdk/build.gradle`.
- **Android Studio can't use AGP 9.2.0**: update Android Studio. The `:sdk` module needs this
  AGP version, so don't lower it without testing the SDK build.
- **Kotlin plugin error**: don't add the `org.jetbrains.kotlin.android` plugin. AGP 9 compiles
  Kotlin itself, and the old plugin makes the build fail.
- **Black screen after Start, or the video does not start until you scan a viewer or press
  Skip in the viewer scanner**: the status bar should say "Press the button to play". If it does
  not appear at all, the 3D view is not drawing; in Android Studio open **Logcat**, type
  `VRVideo` in the search box and look at the messages, from "onCreate" to "first video frame
  received". Send them to whoever is helping you debug.
  If an error message appears and the player closes, the format is probably not supported by
  your phone's decoder.
- **Picture looks warped or the edges blurry**: scan your viewer's QR code (*Set up my viewer*).
- **The 360° video is upside down or mirrored**: it is probably not a plain equirectangular
  (2:1) video. Stereo 360° and VR180 videos are not supported.
- **Playback stutters on 4K 360° video**: use a lower-resolution copy; phones often can't
  decode and render 4K at 60 fps while head tracking.

## Notes

- `gradlew` / `gradle-wrapper.jar` were left out on purpose, because the wrapper jar is an
  opaque binary and could not be verified against Gradle's published checksum when this was
  assembled. If you want command-line builds, run `gradle wrapper` once with a Gradle 9.8
  install.
- Local videos are opened through Android's file picker, so the app needs no storage permission.

## Licence

My code (the `app` folder and the project files) is under the **PolyForm Noncommercial License
1.0.0**: free to use, copy, modify and share for noncommercial purposes, with credit to Sagar
Soneji kept in place, and not for making money. See `LICENSE`. The folders `sdk/`, `java_api/`,
`proto/` and `third_party/` are Google's Cardboard SDK under the Apache License 2.0
(`LICENSE-cardboard`).
