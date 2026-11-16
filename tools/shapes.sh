#!/usr/bin/env bash
# The four shapes of the phone. The Fold's glass in dp: cover 411 x 960, main 859 x 896. The
# emulator can't go past 1920 px, so densities are lowered to keep the dp. Landscape is the same
# glass turned (user_rotation). In each shape the gauge and the Power saving action must be in view.
PKG=com.cerniera.riserva

adb shell settings put system accelerometer_rotation 0

shape() { adb shell wm density "$2"; adb shell wm size "$1"; adb shell settings put system user_rotation "$3"; sleep 3; }

check() {
  for try in 1 2 3 4 5 6; do
    json=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null)
    [ -n "$json" ] && break; sleep 1
  done
  python3 - "$1" "$json" <<'PY'
import sys, json
name = sys.argv[1]
try:
    d = json.loads(sys.argv[2])
except Exception:
    print(f"::error::{name}: no layout recorded"); sys.exit(0)
vw, vh = d["view"]; il, it, ir, ib = d["insets"]; ox, oy = d["origin"]
bad, seen = [], []
for k in ("gauge", "power"):
    if k not in d["parts"]: bad.append(f"{k} not laid out"); continue
    l, t, r, b = d["parts"][k]
    l -= ox; r -= ox; t -= oy; b -= oy
    if l < 0 or t < 0 or r > vw or b > vh - ib: bad.append(f"{k} at {[l, t, r, b]} leaves the view {vw}x{vh} (bottom bar {ib})")
    else: seen.append(k)
lay = "two panes" if d["wide"] else "one column"
print(("::error::" if bad else "::notice::") + f"{name} ({lay}): " + ("; ".join(bad) if bad else ", ".join(seen) + f" in view {vw}x{vh}"))
PY
}

open_app() {
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 5
}

for s in "cover-portrait 823x1920 320 0" "cover-landscape 823x1920 320 1" "main-portrait 1503x1568 280 0" "main-landscape 1503x1568 280 1"; do
  set -- $s
  name=$1
  shape "$2" "$3" "$4"
  open_app
  want=$([ "$4" = 0 ] && echo 0 || echo 1)
  for try in 1 2 3; do
    turned=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null | python3 -c "import sys,json;print(int(json.load(sys.stdin)['rotation'] % 2))" 2>/dev/null)
    [ "$turned" = "$want" ] && break
    echo "rotation not applied (try $try), asking again"
    adb shell settings put system user_rotation 0; sleep 1; adb shell settings put system user_rotation "$4"; sleep 3
    open_app
  done
  [ "$turned" = "$want" ] || echo "::error::$name: the screen never turned" | tee -a shapes.txt
  sleep 1
  adb exec-out screencap -p > "shot-shape-$name.png"
  check "Riserva, $name" | tee -a shapes.txt
done

adb shell settings put system user_rotation 0
adb shell wm size reset; adb shell wm density reset
sleep 3
