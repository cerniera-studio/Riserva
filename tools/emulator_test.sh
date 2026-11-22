#!/usr/bin/env bash
# Run by the emulator job: three hours of made-up readings (an hour unfolded, an hour on the cover,
# an hour asleep), then the gauge, today's line and the rates, in all four shapes. Fails on a crash.
set -x
PKG=com.cerniera.riserva
source tools/emu.sh

adb install -r Riserva.apk
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb logcat -c

# The meter's readings, as Fold Taskbar sends them: every five minutes, 10%/h unfolded, 4%/h on
# the cover, 0.6%/h asleep (charge counter left at 0, so Riserva works from the percentage)
now=$(( $(adb shell date +%s | tr -d '\r') * 1000 ))
send() {  # minutes-ago state level
  adb shell am broadcast -n $PKG/.Reading -a com.cerniera.riserva.READING \
    --el t $(( now - $1 * 60000 )) --es state "$2" --ei level "$3" --ei charge 0 --ei current -400000 --ez charging false >/dev/null
}
for m in $(seq 0 5 60); do send $(( 180 - m )) main $(( 90 - m / 6 )); done
for m in $(seq 0 5 60); do send $(( 120 - m )) cover $(( 80 - m * 4 / 60 )); done
send 60 off 76
send 1 off 76
adb shell am start -n $PKG/.MainActivity
sleep 6; shot home
adb shell input swipe 540 1900 540 700 300; sleep 1; shot home-today
adb shell input swipe 540 1900 540 500 300; sleep 1; shot home-rates
adb shell input swipe 540 1900 540 500 300; sleep 1; shot home-lower

# The reserve lamp: a choice of 3 h with a low tank lights it
send 0 main 22
adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity; sleep 4
tap_text "3 h"; sleep 2; shot reserve
adb shell input swipe 540 600 540 1900 300; sleep 1; shot reserve-top

# The widget at the family's size matrix and the picker's preview (22 Nov 2026)
adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity --ez matrix true >/dev/null; sleep 5
for f in preview_riserva matrix-192x164 matrix-255x164 matrix-255x267 matrix-128x267 matrix-177x226 matrix-385x226 matrix-300x120 matrix-110x110 matrix-110x50; do
  adb exec-out run-as $PKG cat files/$f.png > "shot-$f.png" 2>/dev/null
done

bash tools/shapes.sh

adb logcat -d > app-log.txt
if crashed; then echo "::error::Riserva crashed"; exit 1; fi
