#!/usr/bin/env bash
# Turns off the TV's preinstalled apps that Luno doesn't need, over adb. Nothing is deleted from the
# TV: disabled apps stay installed, and removed apps stay in the system image, so --restore brings
# every one back. Apps the TV doesn't have are skipped.
#   ./debloat.sh            disable and remove the apps listed below
#   ./debloat.sh --dry-run  show what would change, change nothing
#   ./debloat.sh --restore  enable and reinstall them all again
# TV address: TV=<ip>:5555 (required). Install Luno first (./build.sh --install): this turns off the
# stock launcher, and the TV needs a home screen.
# Chromecast, screen mirroring and TV Guard aren't here: Luno's Features menu switches them.
set -euo pipefail

# Disabled: kept installed but can't run (pm disable-user).
DISABLE=(
  com.google.android.apps.tv.launcherx      # Google TV home screen (Luno replaces it)
  com.google.android.tungsten.setupwraith   # Google TV setup, brings the stock home back
  com.tcl.tv.tclhome_passive                # TCL home screen helper
  com.google.android.katniss                # Google Assistant and voice search
  com.google.android.apps.tv.dreamx         # Google TV screensaver
  android.autoinstalls.config.google.gtvpai # installs promoted apps
  com.google.android.play.games             # Google Play Games
  com.amazon.amazonvideo.livingroom         # Prime Video
  com.tcl.esticker                          # TCL store stickers and ads
  com.tcl.ocean.instructions                # TCL manual
  com.tcl.gallery                           # TCL gallery
  com.tcl.gamebar                           # TCL game bar
  com.tcl.channelplus                       # TCL Channel
  com.tcl.browser                           # TCL browser
  com.tcl.partnercustomizer                 # TCL partner apps and rows
  com.tcl.bi                                # TCL usage reporting
  com.tcl.overseasappshow                   # TCL app promotions
  com.tcl.smartalexa                        # Alexa
  com.tcl.ui_mediaCenter                    # TCL media center
)

# Removed for the user: gone from the TV but kept in the system image (pm uninstall --user 0).
REMOVE=(
  com.netflix.ninja                         # Netflix
  au.com.stan.and                           # Stan
  tv.wuaki.apptv                            # Rakuten TV
  com.google.android.videos                 # Google TV (Play Movies)
  com.google.android.youtube.tvmusic        # YouTube Music
  com.google.android.feedback               # Google feedback
  com.google.android.marvin.talkback        # TalkBack
  com.android.camera2                       # Camera
  com.tcl.waterfall.overseas                # TCL waterfall home rows
  com.tcl.t_solo                            # TCL T-Solo
  com.tcl.dashboard                         # TCL dashboard
  com.tcl.messagebox                        # TCL messages
)

MODE=apply
case "${1:-}" in
  "") ;;
  --dry-run) MODE=dry ;;
  --restore) MODE=restore ;;
  *) echo "Usage: TV=<ip>:5555 $0 [--dry-run | --restore]" >&2; exit 1 ;;
esac
TV="${TV:-}"
[ -n "$TV" ] || { echo "Set TV=<ip>:5555, e.g. TV=192.168.1.20:5555 $0" >&2; exit 1; }

adb connect "$TV" >/dev/null
sh() { adb -s "$TV" shell "$@" </dev/null | tr -d '\r'; }
sh true >/dev/null || { echo "Can't reach $TV: turn on network debugging and allow this computer." >&2; exit 1; }

ALL=$(sh pm list packages -u)      # every package in the system, removed ones included
ON=$(sh pm list packages -e)       # installed and enabled
INSTALLED=$(sh pm list packages)   # installed for the user
has() { grep -qxF "package:$1" <<<"$2"; }

if [ "$MODE" != restore ] && has com.google.android.apps.tv.launcherx "$ON" \
   && ! has app.luno.home "$INSTALLED"; then
  echo "Luno isn't installed: run ./build.sh --install first, or the TV has no home screen." >&2
  exit 1
fi

changed=0
run() { # <description> <command...>
  local what="$1"; shift
  if [ "$MODE" = dry ]; then echo "would $what"; else echo "$what"; sh "$@" >/dev/null; fi
  changed=$((changed + 1))
}

for p in "${DISABLE[@]}"; do
  has "$p" "$ALL" || continue
  if [ "$MODE" = restore ]; then
    has "$p" "$ON" || run "enable $p" pm enable --user 0 "$p"
  else
    has "$p" "$ON" && run "disable $p" pm disable-user --user 0 "$p"
  fi
done
for p in "${REMOVE[@]}"; do
  has "$p" "$ALL" || continue
  if [ "$MODE" = restore ]; then
    has "$p" "$INSTALLED" || run "reinstall $p" cmd package install-existing --user 0 "$p"
  else
    has "$p" "$INSTALLED" && run "remove $p" pm uninstall -k --user 0 "$p"
  fi
done

[ "$changed" -gt 0 ] || { echo "Nothing to change."; exit 0; }
[ "$MODE" = dry ] && echo "$changed apps would change." || echo "Done: $changed apps changed."
