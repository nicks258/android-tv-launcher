#!/usr/bin/env bash
set -e

# Configure Android SDK paths
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

MODE="${1:-run}"     # "run", "reload", "emulator-start", "tv"
TARGET="${2:-auto}"  # "auto", "emulator", "tv", or specific device ID

get_target_device() {
    if [ "$TARGET" != "auto" ]; then
        if [ "$TARGET" = "emulator" ]; then
            echo "emulator-5554"
        elif [ "$TARGET" = "tv" ]; then
            echo "192.168.8.108:5555"
        else
            echo "$TARGET"
        fi
        return
    fi

    # Auto-detect: prefer emulator-5554 if running, else first connected device
    if adb devices | grep -q "emulator-5554[[:space:]]\+device"; then
        echo "emulator-5554"
    else
        DEV=$(adb devices | grep -E "^[a-zA-Z0-9.:_-]+[[:space:]]+device" | head -n 1 | awk '{print $1}')
        if [ -n "$DEV" ]; then
            echo "$DEV"
        else
            echo ""
        fi
    fi
}

start_emulator_if_needed() {
    if ! adb devices | grep -q "emulator-5554[[:space:]]\+device"; then
        echo "Starting Television_1080p Android TV emulator..."
        nohup emulator -avd Television_1080p -no-boot-anim >/dev/null 2>&1 &
        echo "Waiting for emulator to boot..."
        adb wait-for-device
        while [ "$(adb -e shell getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
            sleep 2
        done
        echo "Emulator ready!"
    fi
}

DEVICE=$(get_target_device)

case "$MODE" in
    emulator-start)
        start_emulator_if_needed
        exit 0
        ;;
    tv)
        DEVICE="192.168.8.108:5555"
        ;;
    run|reload)
        if [ -z "$DEVICE" ] || [ "$TARGET" = "emulator" ]; then
            start_emulator_if_needed
            DEVICE="emulator-5554"
        fi
        ;;
esac

echo "Target device: $DEVICE"

# Build debug APK incrementally
echo "Building debug APK..."
./gradlew assembleDebug --quiet

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found at $APK_PATH"
    exit 1
fi

echo "Installing APK to $DEVICE..."
adb -s "$DEVICE" install -r "$APK_PATH"

echo "Launching Plume Launcher on $DEVICE..."
adb -s "$DEVICE" shell am start -n dev.plume/.MainActivity

# Forward JDWP port 8700 for debugger attachment
sleep 1
PID=$(adb -s "$DEVICE" shell pidof dev.plume 2>/dev/null | awk '{print $1}')
if [ -n "$PID" ]; then
    adb -s "$DEVICE" forward tcp:8700 jdwp:"$PID" 2>/dev/null || true
    echo "Debugging port forwarded: localhost:8700 -> $DEVICE (PID: $PID)"
fi

if [ "$MODE" = "reload" ]; then
    echo "Hot reload / fast restart complete!"
else
    echo "App launched successfully!"
fi
