# Remote button mapping

Let the user point the remote's app buttons (Prime Video, Netflix, YouTube…) at any app, for
example the Prime Video button opening TVO.

## Why

The app buttons are handled by the TV itself, not by Luno: Luno only sees keys while Home is on
screen (`MainActivity.dispatchKeyEvent`). `debloat.sh` disables Prime Video and removes Netflix, so
their buttons now do nothing or open the Play Store.

## How

- An accessibility service with `flagRequestFilterKeyEvents` gets the remote's keys even while
  another app is open. On a mapped button it consumes the key and opens the chosen app.
- Turning the service on: normally by hand in Settings; Luno can do it itself over `LocalAdb`
  (`settings put secure enabled_accessibility_services …` and `accessibility_enabled 1`).
- Setup from the remote: a "Remote buttons" entry in the Features menu. Press the button to map,
  then pick the app; "Default" gives the button back to the TV. Saved on the TV.
- The service runs in Luno's process: measure the memory it adds against the 12 MB figure.

## Check first

On some TVs the system or the maker (TCL) handles these buttons before accessibility sees them, so
it depends on the TV:

1. Over adb, `getevent -l` while pressing each app button: note the key codes.
2. A bare test service that logs every key it gets: see which buttons reach it.
3. Only build the menu if the buttons that matter (Prime, Netflix, YouTube) reach it.
