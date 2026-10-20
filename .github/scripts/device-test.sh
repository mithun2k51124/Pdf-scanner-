#!/usr/bin/env bash
# Runs on the emulator: installs the app + tests, runs them without uninstalling,
# then collects screenshots, test output and logcat into ./out
set -x
mkdir -p out/screens
adb wait-for-device
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -r com.mithun.latchscan.test/androidx.test.runner.AndroidJUnitRunner > out/instrument.txt 2>&1
# Second pass of the UI walk-through in dark mode (screenshots prefixed dark_)
adb shell cmd uimode night yes
sleep 2
adb shell am instrument -w -r -e class com.mithun.latchscan.UiFlowTest -e shotPrefix dark_ com.mithun.latchscan.test/androidx.test.runner.AndroidJUnitRunner > out/instrument-dark.txt 2>&1
adb shell cmd uimode night no
adb logcat -d -v time > out/logcat-full.txt 2>&1
grep -E "AndroidRuntime|FATAL|latchscan|Latch|OpenCV|TestRunner" out/logcat-full.txt | tail -n 2500 > out/logcat.txt
adb exec-out run-as com.mithun.latchscan sh -c 'cd files/screens && tar cf - .' > out/screens.tar
tar xf out/screens.tar -C out/screens && rm out/screens.tar
ls -la out out/screens
cat out/instrument.txt | tail -n 60
exit 0
