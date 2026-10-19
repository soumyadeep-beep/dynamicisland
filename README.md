# Dynamic Island (Android overlay)

Draws your Dynamic Island design over every app, at the camera cutout.
The design is `app/src/main/assets/index.html`. The same file also powers the full-screen "lab" preview.

## Build the APK

Needs JDK 17+ and the Android SDK (Android Studio installs both).

    ./build.sh              # macOS / Linux  -> dist/DynamicIslandLab-debug.apk
    ./build.sh --install    # build and install to a USB-connected phone
    build.bat               # Windows

No computer? Push this folder to a GitHub repository and open Actions > Build APK > Run workflow.
Download `DynamicIslandLab-debug` from the finished run on your phone and unzip it to get the APK.

## First run on the phone

1. Open **Dynamic Island**.
2. Tap **Allow "Display over other apps"** and switch it on for this app, then go back.
3. Tap **Start island** (allow notifications if asked; this is the small "running" notification).
4. Turn on **Always show black pill**, then use the two sliders until the pill covers your camera hole.
5. Turn the pill setting off if you want it to appear only when something happens.
6. Use the **Test it** buttons to preview Charging, Music, In call, Timer and the rest.

## What reacts to real events

Charger plugged in, battery low, ringer mode (ring / vibrate / silent) and airplane mode.
The other states (music, calls, navigation, rides, scores) are previews for now. Showing real ones
needs a notification-access service, which is a good next step.

## Notes

- Some phones (Xiaomi, Oppo, Vivo, Samsung with battery saving) stop background apps. If the island
  disappears, allow "Display pop-up windows" and set battery use to "Unrestricted" for this app.
- Pull-down gestures and taps outside the island go straight to the app underneath; only the island area is touchable.
- The island does not start by itself after a reboot yet. Open the app and tap Start.
- Debug builds can be inspected from `chrome://inspect` on a computer (the lab screen).
