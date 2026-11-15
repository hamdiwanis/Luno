#!/usr/bin/env bash
# Builds build/ambient.apk with the bare SDK tools (no Gradle).
#   ./build.sh            build
#   ./build.sh --install  build, install on the TV and make it the home screen
# Needs JDK 17 (JAVA_HOME) and the Android SDK with build-tools 34.0.0 and platform 34 (ANDROID_HOME).
# TV address: TV=<ip>:5555 (required for --install).
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
[ -d "$SDK/build-tools/34.0.0" ] || SDK=/opt/homebrew/share/android-commandlinetools
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
TV="${TV:-}"
B=build

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  -A assets --min-sdk-version 28 --target-sdk-version 30 \
  --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-1.0}" "$B/res.zip"
javac -nowarn -Xlint:-options -encoding UTF-8 --release 11 -classpath "$JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$BT/d8" --release --min-api 28 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias ambient -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Ambient" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out "$B/ambient.apk" "$B/aligned.apk"
echo "Built $B/ambient.apk ($(wc -c < "$B/ambient.apk") bytes)"
[ "${1:-}" = "--install" ] || exit 0
[ -n "$TV" ] || { echo "Set TV=<ip>:5555 to install, e.g. TV=192.168.1.20:5555 ./build.sh --install" >&2; exit 1; }

adb connect "$TV" >/dev/null
adb -s "$TV" install -r "$B/ambient.apk"
adb -s "$TV" shell cmd package set-home-activity --user 0 app.ambient.home/.MainActivity
adb -s "$TV" shell am force-stop app.ambient.home
adb -s "$TV" shell input keyevent KEYCODE_HOME
echo "Installed and set as home."
