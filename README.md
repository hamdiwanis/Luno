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
- **Two homes** (Features > Home): **Photo**, the photo with the dock, or **Apps list**, no photo, every
  app as a banner in one grid, the focused app's name large at the bottom and the lower screen washed
  in its colours. The apps list uses about half the memory (about 13 MB).
- **Updates shown on the apps**: an app being installed or updated (Play Store or any other) is
  darkened, with "Updating" and its progress, until it's done.
- **Hide apps**: hold OK on an app and choose Hide; Features > Hidden apps brings it back.
- **Features menu** (top bar, or the remote's Menu key), in three parts:
  - *Home screen*: the home style, the apps order (yours, or recently used first) and hidden apps.
  - *TV*: an app to open when the TV turns on (or the last one used), a sleep timer (15 minutes to
    2 hours) and memory: how much is in use, with a bar; choose it to close background apps.
  - *Background apps*: switch apps such as Chromecast, screen mirroring or TV Guard on and off.
- **Network name** shown without turning Location on, and a **Bluetooth** device's name while one
  is connected.
- **Background limit**: keeps Android's cached-app limit (1 by default) set, as Google's flag sync
  wipes it.

The Features menu, network name, sleep timer and background limit use the TV's own adb (network debugging on
`127.0.0.1:5555`). The first time, the TV asks "Allow USB debugging?" for Luno's key: tick
**Always allow** and press **Allow**.

## Download

A ready-built APK is on the [Releases page](https://github.com/hamdiwanis/Luno/releases/latest)
(Android TV / Google TV, Android 9 or later). Turn on network debugging on the TV (Settings >
System > Developer options), then from a computer with adb:

```bash
adb connect 192.168.1.20:5555                 # your TV's address
adb install luno-1.0.apk
adb shell cmd package set-home-activity --user 0 app.luno.home/.MainActivity
```

Google TV has no home-screen picker, so also turn off the stock home: run `debloat.sh` (below), or
just `adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx` and
`com.google.android.tungsten.setupwraith`. Settings in `config.xml` (dock, apps order) need a build
of your own; everything else can be arranged from the remote.

## Build and install

Needs JDK 17 and the Android SDK (build-tools 34.0.0, platform 34). No Gradle; the code is shrunk
and optimised with R8 (`proguard-rules.pro`; `build/mapping.txt` maps crash traces back).

```bash
./build.sh                                  # build build/luno.apk
TV=192.168.1.20:5555 ./build.sh --install   # install on the TV and make it the home screen
```

On Google TV, disable the stock launcher (and `com.google.android.tungsten.setupwraith`) so the
Home button opens Luno. `debloat.sh` does that for you.

## Debloat the TV

`debloat.sh` turns off the TV's preinstalled apps that Luno doesn't need, over the same adb: the
stock Google TV home and its setup, Assistant, the screensaver, TCL's promotions, store and usage
reporting, and preloaded streaming apps (Netflix, Prime Video, Stan, Rakuten TV and others). It
frees memory and keeps the stock home from coming back. Install Luno first, as it replaces the
stock home screen.

```bash
TV=192.168.1.20:5555 ./debloat.sh --dry-run   # list what would change
TV=192.168.1.20:5555 ./debloat.sh             # disable and remove those apps
TV=192.168.1.20:5555 ./debloat.sh --restore   # bring every one of them back
```

Nothing is deleted from the TV: disabled apps stay installed and removed apps stay in the system
image, so `--restore` (or a factory reset) brings them back. Apps the TV doesn't have are skipped,
so it is safe to run again. Edit the `DISABLE` and `REMOVE` lists at the top of the script to keep
an app you use. Chromecast, screen mirroring and TV Guard aren't in it: switch them in Luno's
Features menu.

## Settings

Everything is in [`res/values/config.xml`](res/values/config.xml): the starting dock and apps-sheet order,
hidden apps, Features apps, photo interval and background limit. Edit, then build and install.

## Mockups

`mockups/render.py` renders 1920×1080 layout mockups with Pillow from a photo, banners cropped
from a TV screenshot and fonts placed in `mockups/src/` (not in the repo).

## Licence

The code is under the [MIT licence](LICENSE). The photos keep their own licences (below).

## Credits

Photos from Wikimedia Commons Featured Pictures under their own licences:
[WALLPAPER-CREDITS.md](WALLPAPER-CREDITS.md). Icons from Material Icons (Apache-2.0).
