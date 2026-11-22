# reYAMF2

Run Android apps in resizable freeform windows on phones and tablets, without the desktop
mode that the system normally requires.

A fork of [YAMF](https://github.com/kaii-lb/YAMFsquared) (which is itself a fork of the
original work by duzhaokun123), rebuilt for current Android with a smaller window chrome.

## Disclaimer

This software is provided as-is, without any warranty. The author does not claim
responsibility for any damage, data loss, instability, or other harm that may result
from using this module on your device or software system. Use at your own risk.

## Requirements

- Android 13+ (API 33)
- A hook manager: **LSPosed** for the legacy flavor, **Vector 2.1+** for the vector flavor
- The accessibility service, enabled after install

## Which APK to install

The module is built for both hook platforms from the same sources. Install **exactly one**:

| APK | Hook platform | Registration |
| --- | --- | --- |
| `reYAMF2-<version>-vector.apk` | Vector 2.1+ | `META-INF/xposed/` (libxposed API 101) |
| `reYAMF2-<version>-legacy.apk` | LSPosed / classic Xposed | `assets/xposed_init` |

Both have the same features. If your hook manager is not Vector, take the legacy APK.

## Install

1. Download the APK that matches your hook manager from the releases page.
2. Install it and enable the module in the hook manager.
3. Open the app, then enable the **reYAMF2 accessibility service** in system settings.
4. Reboot. Existing windows are only safe to keep if the module version did not change.

## What this build changes

### Window chrome

- The three-dot control is a **notch cut into the top edge of the window**, in the style of a
  camera cut-out: a small white shape curves down into the content and holds the dots. There is
  no floating pill anymore.
- The four actions (minimize, fullscreen, collapse, close) live in their own **overlay bubble
  that floats above the window**, outside the window's bounds, so it stays reachable over the
  window and over whatever is underneath.
- Tap the notch to show or hide the bubble, drag the notch to move the window, double tap it to
  close the window.
- The window **resizes from the left, right and bottom edges and from both bottom corners**.
  The edge you did not grab stays where it is, and the hosted display changes resolution while
  you drag, so apps reflow instead of being scaled.

### Performance

- Chrome visibility is no longer polled. Every open window used to run a 500 ms loop that
  rewrote four visibilities; each write invalidated the whole window, so the chrome redrew twice
  a second. Measured on a Galaxy Tab S6 Lite (P613), an **idle open window cost `system_server`
  about 27% CPU**, nearly all of it in its `RenderThread`. With the state-driven update the same
  idle window sits at **about 2%**.
- Per-window memory was measured at **~12 MB RSS** in `system_server`, dominated by the virtual
  display's graphics buffers rather than by the window's own views.

### Under the hood

- One source tree, two hook flavors (`legacy`, `vector`), split by source set.
- Reflection, window registration and density maths moved into shared helpers with unit tests.
- The manager app, quick settings tiles and sidebar follow the same service surface.

## Build

```bash
./gradlew :app:assembleVectorRelease    # Vector 2.1+
./gradlew :app:assembleLegacyRelease    # LSPosed / classic Xposed
```

APKs are written to `app/build/outputs/apk/<flavor>/<buildType>/`. Release builds are unsigned,
so sign them with your own key before installing. Useful tasks:

```bash
./gradlew :app:testVectorDebugUnitTest   # unit tests
./gradlew :app:assembleVectorDebug       # debuggable build for testing a device
```

## API

- Broadcast `com.mja.reyamf.action.CURRENT_TO_WINDOW` to float the currently visible app.
- Broadcast `com.mja.reyamf.action.OPEN_APP` with `componentName`, `userId` (and optionally
  `taskId`) to open a given activity in a window.
- Broadcast `com.mja.reyamf.action.RESET_ALL_WINDOW` to close every window.

## Known issues

- The system can crash if the installed module version differs from the injected one; reboot
  after every module update.
- Some apps refuse to launch in small windows or scale abnormally at certain sizes.
- Some apps restart while being resized.
- After collapsing a window to its app icon, tapping the icon does not always restore it; the
  minimize state does restore.
- Keyboard might not show up when trying to type inside the floating window
- Navigation buttons acts on the background application even when the foreground window is focused

## Credits

- [duzhaokun123](https://github.com/duzhaokun123) and [kaii-lb](https://github.com/kaii-lb/YAMFsquared)
  for the original YAMF / YAMFsquared, and [JuanArton](https://github.com/JuanArton) for the
  fork this one started from.
- [AOSP](https://source.android.com/), [LSPosed](https://github.com/LSPosed/LSPosed),
  [EzXHelper](https://github.com/KyuubiRan/EzXHelper), [Mi-FreeForm](https://github.com/sunshine0523/Mi-FreeForm),
  [QAuxiliary](https://github.com/cinit/QAuxiliary), [FlexboxLayout](https://github.com/google/flexbox-layout),
  [Hide-My-Applist](https://github.com/Dr-TSNG/Hide-My-Applist),
  [ViewBindingUtil](https://github.com/matsudamper/ViewBindingUtil), [gson](https://github.com/google/gson),
  [Material](https://material.io/).
