#!/bin/bash
# Script to compile and copy the compiled APKs to /output and local ./output directory

set -e

echo "=== Running Aero Player Build Process ==="

echo "1. Building Debug APK..."
gradle :app:assembleDebug

echo "2. Building Release APK..."
gradle :app:assembleRelease

echo "3. Creating system destination directory: /output"
mkdir -p /output

echo "4. Creating local destination directory: ./output"
mkdir -p ./output

# Detect newest APK from standard paths and workspace
copied=0

# Check release APK
RELEASE_PATHS=(
    "app/build/outputs/apk/release/app-release.apk"
    ".build-outputs/app-release.apk"
)
for path in "${RELEASE_PATHS[@]}"; do
    if [ -f "$path" ]; then
        echo "Found release APK at: $path"
        cp "$path" /output/app-release.apk
        cp "$path" ./output/app-release.apk
        copied=1
        break
    fi
done

# Check debug APK
DEBUG_PATHS=(
    "app/build/outputs/apk/debug/app-debug.apk"
    ".build-outputs/app-debug.apk"
)
for path in "${DEBUG_PATHS[@]}"; do
    if [ -f "$path" ]; then
        echo "Found debug APK at: $path"
        cp "$path" /output/app-debug.apk
        cp "$path" ./output/app-debug.apk
        copied=1
        break
    fi
done

# Fallback: search for other .apk files dynamically, excluding output directories
if [ "$copied" -eq 0 ]; then
    while IFS= read -r file; do
        if [ -n "$file" ] && [ -f "$file" ]; then
            name=$(basename "$file")
            echo "Found APK at: $file"
            cp "$file" /output/"$name"
            cp "$file" ./output/"$name"
            copied=1
        fi
    done < <(find . -name "*.apk" -not -path "*/intermediates/*" -not -path "./output/*" -not -path "*/output/*" 2>/dev/null)
fi

if [ "$copied" -ne 0 ]; then
    echo "Successfully compiled and copied APK(s) to /output and ./output"
    ls -lh ./output
else
    echo "Error: No APK file found."
    exit 1
fi
