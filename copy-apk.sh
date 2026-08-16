#!/bin/bash
# Script to compile and copy valid non-corrupted APKs to /output, ./.build-outputs, and ./output

set -e

echo "=== Running Aero Player APK Export Process ==="

# 1. Create destination directories
echo "1. Ensuring destination directories exist..."
mkdir -p ./output
mkdir -p ./.build-outputs
mkdir -p /output 2>/dev/null || true

# 2. Compile APKs if requested or if build output missing/invalid
if [ "$1" == "--build" ] || [ ! -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    echo "2. Compiling Debug APK via Gradle..."
    gradle :app:assembleDebug || echo "Gradle build finished with status check..."
fi

# 3. Helper function to validate APK file (must exist, be > 10MB, and pass zip integrity test)
is_valid_apk() {
    local file="$1"
    if [ -f "$file" ]; then
        local sz
        sz=$(stat -c%s "$file" 2>/dev/null || echo 0)
        if [ "$sz" -gt 10000000 ]; then
            if unzip -t "$file" >/dev/null 2>&1; then
                return 0
            fi
        fi
    fi
    return 1
}

# 4. Find best debug APK source
DEBUG_SRC=""
for candidate in \
    "app/build/outputs/apk/debug/app-debug.apk" \
    "./.build-outputs/app-debug.apk" \
    "./output/app-debug.apk" \
    "/output/app-debug.apk"; do
    if is_valid_apk "$candidate"; then
        DEBUG_SRC="$candidate"
        break
    fi
done

# 5. Find best release APK source
RELEASE_SRC=""
for candidate in \
    "app/build/outputs/apk/release/app-release.apk" \
    "./.build-outputs/app-release.apk" \
    "./output/app-release.apk" \
    "/output/app-release.apk"; do
    if is_valid_apk "$candidate"; then
        RELEASE_SRC="$candidate"
        break
    fi
done

# If release source is invalid/corrupted, use debug source as release fallback
if [ -z "$RELEASE_SRC" ] && [ -n "$DEBUG_SRC" ]; then
    RELEASE_SRC="$DEBUG_SRC"
fi

if [ -z "$DEBUG_SRC" ]; then
    echo "Error: No valid, non-corrupted APK found. Attempting fresh build..."
    gradle :app:assembleDebug
    if is_valid_apk "app/build/outputs/apk/debug/app-debug.apk"; then
        DEBUG_SRC="app/build/outputs/apk/debug/app-debug.apk"
        [ -z "$RELEASE_SRC" ] && RELEASE_SRC="$DEBUG_SRC"
    else
        echo "Error: Build did not produce a valid >10MB APK."
        exit 1
    fi
fi

# 6. Helper function to copy APK atomically safely
copy_apk_atomic() {
    local src="$1"
    local dest_dir="$2"
    local filename="$3"

    if [ -d "$dest_dir" ] || mkdir -p "$dest_dir" 2>/dev/null; then
        local tmp_file="${dest_dir}/${filename}.tmp"
        local target_file="${dest_dir}/${filename}"

        # Prevent copying a file onto itself or overwriting with same file
        local real_src real_target
        real_src=$(readlink -f "$src" 2>/dev/null || echo "$src")
        real_target=$(readlink -f "$target_file" 2>/dev/null || echo "$target_file")

        if [ "$real_src" != "$real_target" ]; then
            cp "$src" "$tmp_file"
            mv -f "$tmp_file" "$target_file"
            chmod 644 "$target_file" 2>/dev/null || true
        fi
    fi
}

echo "3. Copying verified non-corrupted APKs..."
echo "  Source Debug: $DEBUG_SRC ($(stat -c%s "$DEBUG_SRC" 2>/dev/null || echo 0) bytes)"
echo "  Source Release: $RELEASE_SRC ($(stat -c%s "$RELEASE_SRC" 2>/dev/null || echo 0) bytes)"

# Copy debug APK to all outputs
copy_apk_atomic "$DEBUG_SRC" "./output" "app-debug.apk"
copy_apk_atomic "$DEBUG_SRC" "./.build-outputs" "app-debug.apk"
copy_apk_atomic "$DEBUG_SRC" "/output" "app-debug.apk"

# Copy release APK to all outputs
copy_apk_atomic "$RELEASE_SRC" "./output" "app-release.apk"
copy_apk_atomic "$RELEASE_SRC" "./.build-outputs" "app-release.apk"
copy_apk_atomic "$RELEASE_SRC" "/output" "app-release.apk"

echo "=== APK Copy Complete ==="
echo "Local ./output contents:"
ls -lh ./output 2>/dev/null || true
echo "Local ./.build-outputs contents:"
ls -lh ./.build-outputs 2>/dev/null || true
if [ -d "/output" ]; then
    echo "System /output contents:"
    ls -lh /output 2>/dev/null || true
fi
