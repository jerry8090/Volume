# Volume Master

On-screen volume control for phones with broken volume buttons.
Dashboard + OPPO-style edge floating controller + notification buttons, all synchronized.
Kotlin, XML/Material 3, minSdk 23, targetSdk 34. No root, no internet permission, no accessibility service.

## Build the APK

**Option A - Android Studio (recommended)**
1. Install Android Studio (Koala or newer) - it bundles JDK 17.
2. File > Open > select the `VolumeMaster` folder. Let Gradle sync finish (needs internet once).
   If sync complains about the Gradle wrapper, set Settings > Build > Gradle > "Use Gradle from" to the
   default wrapper properties (Gradle 8.7) or run `gradle wrapper` once in a terminal.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. APK: `app/build/outputs/apk/debug/app-debug.apk`

**Option B - GitHub Actions (no local setup)**
Push this folder to a GitHub repo; the workflow in `.github/workflows/build.yml` builds the APK. Download it from the run's Artifacts.

## Install on the Vivo Y20s
1. Copy `app-debug.apk` to the phone (USB, Drive, Telegram...).
2. Open it from a file manager; allow "Install unknown apps" for that file manager when asked. (Or `adb install app-debug.apk`.)
3. If Vivo's security scan warns, choose "Continue install" - it is a debug-signed build.

## Grant permissions
- **Overlay:** Dashboard > turn on "Floating controller" > Open settings > enable "Display over other apps" (Vivo may call it "Display over other apps" / "Floating windows") > go back.
- **Notifications:** Android 11 needs nothing. On Android 13+ tap Continue / "Allow notifications".
- **Do Not Disturb access (optional but recommended):** tap "Allow Do Not Disturb access" and enable Volume Master. Without it Android may refuse ringtone/alarm changes while DND is on; the app then tells you what volume Android kept.
- **Keep it alive on Vivo (Funtouch OS):** Settings > Battery > Background power consumption management > Volume Master > "Allow high background power consumption". Also enable Autostart for Volume Master if you want start-after-reboot.

## Testing checklist
1. Dashboard sliders / +/- / mute for Media, Ringtone, Alarm - compare with Settings > Sound.
2. Edge handle: tap to slide the panel out, tap again (or wait for auto-collapse) to collapse; drag it up/down, drag across the screen to switch edges, close the app, reopen - edge and height are remembered.
3. Panel sliders and Mute / Normal / Max (act on the highlighted row); the chevron collapses, the power icon turns the controller off.
4. Notification buttons: Vol -, Vol +, Mute, Switch (cycles Media > Ringtone > Alarm). Close the app first to confirm they still work.
5. Change volume in one place and confirm the other two update.
6. Open another app - edge handle and notification stay.
7. Turn the switches off/on (service stops/starts, no duplicate bubble).
8. Revoke overlay permission: app shows "Needs permission", no crash.
9. Deny notification permission (Android 13+): app keeps working, status line explains.
10. Force-stop and reopen; reboot with "Start after reboot" enabled.

## Project layout
- `audio/` VolumeManager (AudioManager wrapper), VolumeCategory, VolumeObserver (external changes), VolumeEvents (in-app sync)
- `overlay/FloatingWindowManager.kt` - WindowManager overlay (TYPE_APPLICATION_OVERLAY)
- `notification/` VolumeNotification (channel + actions), NotificationActionHandler
- `receiver/` VolumeActionReceiver (notification buttons), BootReceiver
- `service/` VolumeForegroundService (owns notification + overlay), ServiceController
- `utils/` PermissionManager, VolumePreferences (SharedPreferences), UiExt
- `MainActivity.kt`, `SettingsActivity.kt`

Design note: one foreground service owns both the notification and the overlay, which guarantees a single bubble and a single notification.

## Known Android limits
- Ringtone/alarm can be blocked by Do Not Disturb or OEM limits; the app reports what Android actually applied.
- Setting ringtone to 0 may switch the phone to vibrate/silent mode.
- Swiping the app away or aggressive battery savers on some Vivo builds can kill the service until the app is reopened.

## Volume diagnostics
All volume changes go through `VolumeManager.get(context)`. Logcat (`adb logcat -s VolumeManager`) prints, per change:
stream, requested index, max, value before/after, and whether `setStreamVolume` or the `adjustStreamVolume` fallback applied it.
If Android still refuses, a `diagnosis` line lists fixed-volume, Do Not Disturb filter/access, ringer mode, app-op mode and output devices,
and the on-screen message names the matching reason.

## Web-based panel (HTML/CSS/JS)
The expanded panel is a WebView page in `app/src/main/assets/volume-ui/` (`index.html`, `css/style.css`,
`css/floating-controller.css`, `js/android-bridge.js`, `js/volume-controller.js`, `icons/`).
Kotlin owns the window, drag handle and every AudioManager call; the page only talks to `window.VolumeBridge`
(`overlay/VolumeWebBridge.kt`), which validates category names and values. To restyle the panel, edit the CSS/HTML only.
Open `index.html` in a desktop browser to preview it with the built-in mock bridge.
Debug builds allow `chrome://inspect` on the WebView.


## Edge swipe (OPPO Smart Sidebar style)

- **Swipe the handle inward** (left from the right edge, right from the left edge): the panel slides out of the
  edge and follows your finger. Release past the threshold (default 35% of the panel travel, `VolumePreferences.swipeThresholdPercent`)
  or flick quickly and it settles open; otherwise it settles closed.
- **Swipe the open panel back toward its edge** to collapse it. Swipes that start on the slider or the +/- buttons
  are ignored (the page reports those areas through `VolumeBridge.setNoSwipeZones`).
- **Tap the handle** to expand/collapse. **Drag the handle up/down** to slide it along the edge.
  **Long-press, then drag** to move it to the other edge (Settings still has the edge option too).
- If your phone uses gesture navigation, a swipe from the very edge can trigger Back. The handle asks the system to
  exclude its area (Android 10+), but some OEMs ignore that for overlays. If Back fires instead, lower the
  system "back gesture sensitivity" or switch to 3-button navigation.
