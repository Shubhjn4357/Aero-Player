#!/usr/bin/env bash
set -euo pipefail

# Script to rename package from com.example (or old package) to com.aerotech.aeroplayer
# Supports single run execution and folder structure migration.

OLD_PKG="${1:-com.example}"
NEW_PKG="${2:-com.aerotech.aeroplayer}"

OLD_PATH="${OLD_PKG//.//}"
NEW_PATH="${NEW_PKG//.//}"

echo "=========================================="
echo " Aero Player Package Renaming Tool"
echo " Old Package: $OLD_PKG ($OLD_PATH)"
echo " New Package: $NEW_PKG ($NEW_PATH)"
echo "=========================================="

# 1. Update text occurrences in source files, manifests, and gradle files
echo "[1/4] Updating package occurrences in source code..."
find app/src -type f \( -name "*.kt" -o -name "*.java" -o -name "*.xml" \) -exec sed -i "s|${OLD_PKG}|${NEW_PKG}|g" {} +

# 2. Update build.gradle.kts
echo "[2/4] Updating app/build.gradle.kts..."
sed -i "s|namespace = \"${OLD_PKG}\"|namespace = \"${NEW_PKG}\"|g" app/build.gradle.kts
sed -i "s|applicationId = \".*\"|applicationId = \"${NEW_PKG}\"|g" app/build.gradle.kts
sed -i "s|versionCode = [0-9]*|versionCode = 10406|g" app/build.gradle.kts
sed -i "s|versionName = \".*\"|versionName = \"1.4.6\"|g" app/build.gradle.kts

# 3. Migrate folders in main, test, androidTest
echo "[3/4] Migrating directory trees..."
for src_set in main test androidTest; do
    DIR="app/src/${src_set}/java"
    if [ -d "${DIR}/${OLD_PATH}" ]; then
        echo " -> Migrating ${DIR}/${OLD_PATH} to ${DIR}/${NEW_PATH}"
        mkdir -p "${DIR}/${NEW_PATH}"
        # Move all contents
        find "${DIR}/${OLD_PATH}" -mindepth 1 -maxdepth 1 -exec mv {} "${DIR}/${NEW_PATH}/" \;
        # Remove old directories if empty
        rmdir -p "${DIR}/${OLD_PATH}" 2>/dev/null || true
    fi
done

echo "[4/4] Package migration completed successfully to ${NEW_PKG}!"
