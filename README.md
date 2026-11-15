# Luno

A light, photo-first home screen for Android TV / Google TV: a full-screen photo that changes
every 10 minutes, a compact top bar (time, date, network, Features, Settings) and a floating dock
of your favourite apps; press Down for every other app.

Built for low-memory TVs (tested on a 2 GB TCL Google TV, Android 11): plain Java, no libraries,
about 16 MB of its own memory on Home and 12 MB while another app is in front.

## Features

- **Dock** of your apps, with an even gap around the focused app, a white focus ring and the
  others dimmed.
- **Apps sheet** (Down from the dock): chosen apps first, then the rest A to Z; system helpers
  hidden. Built only when opened.
- **Arrange from the remote**: hold OK on a dock app to move it or remove it from the dock; hold OK
  in the apps sheet to move an app within the list or add it to the dock (up to 6). Saved on the TV;
  `config.xml` only sets the starting order.
- **Features menu** (top bar, or the remote's Menu key): switch apps such as Chromecast, screen
  mirroring or TV Guard on and off.
- **Network name** shown without turning Location on.
- **Background limit**: keeps Android's cached-app limit (1 by default) set, as Google's flag sync
  wipes it.

The Features menu, network name and background limit use the TV's own adb (network debugging on
`127.0.0.1:5555`). The first time, the TV asks "Allow USB debugging?" for Luno's key: tick
**Always allow** and press **Allow**.

## Build and install

Needs JDK 17 and the Android SDK (build-tools 34.0.0, platform 34). No Gradle.

```bash
./build.sh                                  # build build/ambient.apk
TV=192.168.1.20:5555 ./build.sh --install   # install on the TV and make it the home screen
```

On Google TV, disable the stock launcher (and `com.google.android.tungsten.setupwraith`) so the
Home button opens Luno.

## Settings

Everything is in [`res/values/config.xml`](res/values/config.xml): the starting dock and apps-sheet order,
hidden apps, Features apps, photo interval and background limit. Edit, then build and install.

## Mockups

`mockups/render.py` renders 1920×1080 layout mockups with Pillow from a photo, banners cropped
from a TV screenshot and fonts placed in `mockups/src/` (not in the repo).

## Credits

Photos from Wikimedia Commons Featured Pictures under their own licences:
[WALLPAPER-CREDITS.md](WALLPAPER-CREDITS.md). Icons from Material Icons (Apache-2.0).
