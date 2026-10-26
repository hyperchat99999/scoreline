#!/usr/bin/env bash
# Builds Scoreline.apk without Gradle: aapt2 (resources) -> javac -> d8 (dex) -> zipalign -> apksigner.
# Needs: Microsoft OpenJDK 21, Android build-tools 36 and platform 36 (unzipped under $SDK).
set -euo pipefail
cd "$(dirname "$0")"
# The Windows build tools need Git Bash to translate /c/... paths; adb sessions often disable that.
unset MSYS_NO_PATHCONV

JDK=$(ls -d "/c/Program Files/Microsoft/jdk-21"* | head -1)
SDK=${SDK:-$HOME/AppData/Local/Temp/adsb/sdk}
BT="$SDK/android-16"            # build-tools r36 unzips to this folder name
AJ="$SDK/android-36/android.jar"
BASE=${OUT:-$HOME/AppData/Local/Temp/adsb/build-scoreline}
KS=scoreline-release.keystore
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-1.0}

# A fresh folder per build: on Windows, deleting and recreating the same one races file indexers.
# Previous build outputs are retained; cleanup is an explicit separate operation.
OUT="$BASE/run-$(date +%s)"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "1/6 resources"
"$BT/aapt2.exe" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2.exe" link -I "$AJ" --manifest AndroidManifest.xml --java "$OUT/gen" \
    --min-sdk-version 31 --target-sdk-version 36 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -o "$OUT/unsigned.apk" "$OUT/res.zip"

echo "2/6 compile"
"$JDK/bin/javac" -Xlint:all,-options,-serial -encoding UTF-8 -source 17 -target 17 \
    -classpath "$AJ" -d "$OUT/classes" \
    $(find src -name '*.java') "$OUT/gen/app/scoreline/R.java"

echo "3/6 dex"
"$JDK/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 31 --lib "$AJ" \
    --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "4/6 package"
python -I -c "import sys, zipfile; z = zipfile.ZipFile(sys.argv[1], 'a', zipfile.ZIP_DEFLATED); z.write(sys.argv[2], 'classes.dex'); z.close()" \
    "$OUT/unsigned.apk" "$OUT/dex/classes.dex"
"$BT/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "5/6 key"
[ -f "$KS" ] || "$JDK/bin/keytool" -genkeypair -keystore "$KS" -alias scoreline -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass scoreline -keypass scoreline -dname "CN=Scoreline" >/dev/null 2>&1

echo "6/6 sign"
"$JDK/bin/java" -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass pass:scoreline --key-pass pass:scoreline \
    --out Scoreline.apk "$OUT/aligned.apk"
"$JDK/bin/java" -jar "$BT/lib/apksigner.jar" verify Scoreline.apk && echo "Built $(pwd)/Scoreline.apk ($(wc -c < Scoreline.apk) bytes)"
