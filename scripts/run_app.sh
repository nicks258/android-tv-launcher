#!/usr/bin/env bash
set -e

# Ensure we run from android-launcher project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# Configure Android SDK paths
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

# Configure Java Home if not already set
if [ -z "${JAVA_HOME:-}" ] || [ ! -d "${JAVA_HOME:-}" ]; then
    if [ -d "/opt/homebrew/opt/openjdk@17" ]; then
        export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
    elif [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
        export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    fi
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

MODE="${1:-run}"     # "run", "reload", "emulator-start", "tv", "connect-tv"
TARGET="${2:-auto}"  # "auto", "emulator", "tv", or specific device IP/ID

# Support default TV IP and Port from environment
TV_IP="${TV_IP:-192.168.8.108}"
TV_PORT="${TV_PORT:-5555}"

# Normalize MODE and TARGET
if [ "$MODE" = "tv" ]; then
    MODE="run"
    TARGET="tv"
fi

get_target_device() {
    if [ "$TARGET" != "auto" ]; then
        if [ "$TARGET" = "emulator" ]; then
            local RUNNING_EMU
            RUNNING_EMU=$(adb devices | grep -E "^emulator-[0-9]+[[:space:]]+device" | head -n 1 | awk '{print $1}')
            echo "${RUNNING_EMU:-emulator-5554}"
        elif [ "$TARGET" = "tv" ]; then
            echo "$TV_IP:$TV_PORT"
        elif [[ "$TARGET" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "$TARGET:$TV_PORT"
        else
            echo "$TARGET"
        fi
        return
    fi

    # Auto-detect: prefer emulator if running, else first connected device
    local EMU
    EMU=$(adb devices | grep -E "^emulator-[0-9]+[[:space:]]+device" | head -n 1 | awk '{print $1}')
    if [ -n "$EMU" ]; then
        echo "$EMU"
        return
    fi

    local DEV
    DEV=$(adb devices | grep -E "^[a-zA-Z0-9.:_-]+[[:space:]]+device" | head -n 1 | awk '{print $1}')
    if [ -n "$DEV" ]; then
        echo "$DEV"
    else
        echo ""
    fi
}

start_emulator_if_needed() {
    if ! adb devices | grep -q -E "emulator-[0-9]+[[:space:]]+device"; then
        local AVD_TO_RUN="${AVD_NAME:-}"
        if [ -z "$AVD_TO_RUN" ]; then
            AVD_TO_RUN=$(emulator -list-avds 2>/dev/null | grep -iE "television|tv" | head -n 1)
        fi
        if [ -z "$AVD_TO_RUN" ]; then
            AVD_TO_RUN=$(emulator -list-avds 2>/dev/null | head -n 1)
        fi
        if [ -z "$AVD_TO_RUN" ]; then
            echo "Error: No Android AVD emulator found. Please create one in Android Studio."
            exit 1
        fi
        echo "Starting $AVD_TO_RUN Android TV emulator..."
        nohup emulator -avd "$AVD_TO_RUN" -no-boot-anim >/dev/null 2>&1 &
        echo "Waiting for emulator to boot..."
        adb -e wait-for-device
        while [ "$(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
            sleep 2
        done
        echo "Emulator ready!"
    fi
}

connect_tv_if_needed() {
    local DEV="$1"
    if [[ "$DEV" == *":"* ]]; then
        local HOST="${DEV%%:*}"
        local PORT="${DEV##*:}"
        echo "Checking network connectivity to Android TV at $DEV..."
        if ! nc -z -G 2 "$HOST" "$PORT" 2>/dev/null; then
            echo "❌ Error: Cannot reach Android TV at $DEV."
            echo "Please ensure:"
            echo "  1. The TV is turned on and connected to your Wi-Fi network."
            echo "  2. Developer Options -> Network / ADB Debugging is enabled on the TV."
            echo "  3. The IP address matches your TV's current IP (current: $HOST)."
            exit 1
        fi
        echo "TV reachable. Connecting with ADB..."
        adb connect "$DEV"
        sleep 1
        local STATE
        STATE=$(adb devices | grep "^$DEV[[:space:]]" | awk '{print $2}')
        if [ "$STATE" = "offline" ] || [ "$STATE" = "unauthorized" ]; then
            echo "Warning: TV $DEV is currently '$STATE'."
            echo "Please confirm the 'Allow USB / Network debugging?' prompt on your TV screen."
        elif [ "$STATE" != "device" ]; then
            echo "Note: TV state is '$STATE'."
        else
            echo "Successfully connected to $DEV!"
        fi
    fi
}

DEVICE=$(get_target_device)

case "$MODE" in
    emulator-start)
        start_emulator_if_needed
        exit 0
        ;;
    connect-tv)
        if [ -z "$DEVICE" ] || [ "$TARGET" = "auto" ] || [ "$TARGET" = "tv" ]; then
            DEVICE="$TV_IP:$TV_PORT"
        fi
        connect_tv_if_needed "$DEVICE"
        exit 0
        ;;
    run|reload)
        if [ "$TARGET" = "emulator" ] || ([ "$TARGET" = "auto" ] && [ -z "$DEVICE" ]); then
            start_emulator_if_needed
            DEVICE=$(get_target_device)
            [ -z "$DEVICE" ] && DEVICE="emulator-5554"
        elif [ "$TARGET" = "tv" ] || [[ "$DEVICE" == *":"* ]]; then
            connect_tv_if_needed "$DEVICE"
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

if [ "$MODE" = "reload" ]; then
    echo "Stopping existing instance on $DEVICE..."
    adb -s "$DEVICE" shell am force-stop dev.plume 2>/dev/null || true
fi

echo "Installing APK to $DEVICE..."
adb -s "$DEVICE" install -r "$APK_PATH"

echo "Launching Plume Launcher on $DEVICE..."
adb -s "$DEVICE" shell am start -n dev.plume/.MainActivity

# Optional: grant secure settings permission if not granted
adb -s "$DEVICE" shell pm grant dev.plume android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true

# Forward JDWP port 8700 for debugger attachment
sleep 1
PID=$(adb -s "$DEVICE" shell pidof dev.plume 2>/dev/null | awk '{print $1}' | tr -d '\r')
if [ -n "$PID" ]; then
    adb -s "$DEVICE" forward tcp:8700 jdwp:"$PID" 2>/dev/null || true
    echo "Debugging port forwarded: localhost:8700 -> $DEVICE (PID: $PID)"
fi

if [ "$MODE" = "reload" ]; then
    echo "Hot reload / fast restart complete!"
else
    echo "App launched successfully on $DEVICE!"
fi
