#!/bin/bash
# tv.sh k <keycodes...> | s <name> (screenshot to ~/Downloads/rev) | t (visible texts)
D=${FIXTURE_SHOTS:-.}
case $1 in
 k) shift; for x in "$@"; do adb shell input keyevent $x; sleep 0.6; done;;
 s) sleep ${3:-1}; adb exec-out screencap -p > $D/$2.png;;
 t) adb shell uiautomator dump /sdcard/u.xml >/dev/null; adb shell cat /sdcard/u.xml | grep -o 'text="[^"]\+"' | cut -d'"' -f2 | tr '\n' '|';;
esac
