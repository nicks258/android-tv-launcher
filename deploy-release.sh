#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools:/opt/homebrew/bin"

# Parse arguments: target TV IP or --firebase
TV_IP=""
DEPLOY_FIREBASE=false

for arg in "$@"; do
    if [ "$arg" == "--firebase" ]; then
        DEPLOY_FIREBASE=true
    elif [ -z "$TV_IP" ] && [[ "$arg" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+(:[0-9]+)?$ ]]; then
        TV_IP="$arg"
    fi
done

TV_IP="${TV_IP:-${1:-192.168.8.108:5555}}"
if [ "$1" == "--firebase" ]; then
    TV_IP="192.168.8.108:5555"
fi

echo "=================================================="
echo "🚀 Building Plume TV Launcher Release Build (R8 ProGuard)"
echo "Target TV: $TV_IP"
echo "Deploy to Firebase: $DEPLOY_FIREBASE"
echo "=================================================="

cd "$DIR"

# Determine version code and name from tags or fallback
LATEST_TAG=$(git tag -l "v1.6.*" 2>/dev/null | sort -V | tail -n 1)
LATEST_NUM=6
if [ -n "$LATEST_TAG" ]; then
    TAG_NUM=$(echo "$LATEST_TAG" | sed 's/v1\.6\.//')
    if [ "$TAG_NUM" -ge "$LATEST_NUM" ] 2>/dev/null; then
        LATEST_NUM=$TAG_NUM
    fi
fi
VERSION_NAME="1.6.${LATEST_NUM}"
VERSION_CODE=$((800 + LATEST_NUM))

# Assemble Release APK
echo "📦 Assembling Release APK (version $VERSION_NAME, code $VERSION_CODE)..."
./gradlew assembleRelease -PversionCode=$VERSION_CODE -PversionName="$VERSION_NAME" --daemon --parallel -x test -x lint

RELEASE_APK="$DIR/app/build/outputs/apk/release/app-release.apk"

if [ ! -f "$RELEASE_APK" ]; then
    echo "❌ Error: Release APK not found at $RELEASE_APK"
    exit 1
fi

APK_SIZE=$(du -h "$RELEASE_APK" | cut -f1)
echo "✅ Release APK assembled successfully! Size: $APK_SIZE"

# Update distribution payload
mkdir -p "$DIR/distribution/launcher"
cp "$RELEASE_APK" "$DIR/distribution/launcher.bin"

BYTES=$(stat -c%s "$RELEASE_APK" 2>/dev/null || stat -f%z "$RELEASE_APK")
SHA256=$(sha256sum "$RELEASE_APK" | awk '{print $1}')
RELEASE_DATE=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

cat <<EOF > "$DIR/distribution/launcher-version.json"
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "apkUrl": "https://iptv-harbor.web.app/launcher.bin",
  "apkSize": $BYTES,
  "sha256": "$SHA256",
  "releasedAt": "$RELEASE_DATE",
  "releaseNotes": "Plume TV Launcher release with Firebase Hosting OTA updates and FileProvider installer."
}
EOF
cp "$DIR/distribution/launcher-version.json" "$DIR/distribution/launcher/version.json"
echo "✅ Distribution payload updated in $DIR/distribution"

# Firebase Hosting Deployment if requested
if [ "$DEPLOY_FIREBASE" = true ]; then
    echo "🔥 Deploying to Firebase Hosting (iptv-harbor)..."
    # Ensure live Onyx TV assets are preserved if missing locally
    if [ ! -f "$DIR/distribution/onyx-tv.bin" ]; then
        curl -s -f "https://iptv-harbor.web.app/onyx-tv.bin" -o "$DIR/distribution/onyx-tv.bin" || true
    fi
    if [ ! -f "$DIR/distribution/version.json" ]; then
        curl -s -f "https://iptv-harbor.web.app/version.json" -o "$DIR/distribution/version.json" || true
    fi
    npx --yes firebase-tools deploy --only hosting --project iptv-harbor
    echo "🎉 Successfully deployed to https://iptv-harbor.web.app/"
fi

# ADB Installation if adb is present and TV reachable
if command -v adb &> /dev/null; then
    echo "🔍 Checking ADB connection to $TV_IP..."
    if ! adb devices | grep -q "$TV_IP"; then
        echo "Connecting to $TV_IP..."
        adb connect "$TV_IP" || true
    fi

    if adb devices | grep -q "$TV_IP"; then
        echo "📲 Installing Plume Launcher Release APK to $TV_IP..."
        adb -s "$TV_IP" install -r "$RELEASE_APK" || echo "⚠️ ADB install returned non-zero code."
        echo "📺 Launching Plume TV Launcher on $TV_IP..."
        adb -s "$TV_IP" shell am start -n dev.plume/.MainActivity || true
        echo "=================================================="
        echo "🎉 Plume TV Launcher Release Build is live on your TV!"
        echo "=================================================="
    else
        echo "ℹ️ Target TV $TV_IP not connected via ADB (skipping direct push)."
    fi
else
    echo "ℹ️ ADB not found. Build is ready at $RELEASE_APK."
fi
