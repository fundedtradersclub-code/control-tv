#!/usr/bin/env bash
# Compila ControlTV.apk sin Gradle, solo con las herramientas del Android SDK.
# Uso (Git Bash):  bash build.sh
set -euo pipefail
cd "$(dirname "$0")"

JDK=${JDK:-/c/Users/PC/tools/jdk17/bin}
SDK=${ANDROID_SDK:-/c/Users/PC/tools/android-sdk}
# d8/apksigner (.bat) necesitan JAVA_HOME
export JAVA_HOME="$(cygpath -w "$JDK/.." 2>/dev/null || echo "$JDK/..")"
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
OUT=build
# Clave de firma de esta app casera (no es una clave de ningun servicio)
KS=controltv.keystore; KS_PASS=controltv123

rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex

echo "1/6 recursos"
"$BT/aapt2.exe" compile --dir res -o $OUT/res.zip
"$BT/aapt2.exe" link -o $OUT/base.apk -I "$JAR" --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 26 --target-sdk-version 34 --version-code 7 --version-name 1.6 $OUT/res.zip

echo "2/6 java"
# Genera Config.java con la IP/MAC de ../config.json (archivo local, fuera de git)
mkdir -p $OUT/gen/ec/controltv
python - <<'PY'
import json, pathlib
try:
    cfg = json.loads(pathlib.Path("../config.json").read_text(encoding="utf-8"))
except (OSError, ValueError):
    cfg = {}
ip = str(cfg.get("tv_ip", "")).replace('"', "")
mac = str(cfg.get("tv_mac", "")).replace('"', "")
nombre = str(cfg.get("tv_nombre", "Mi TV")).replace('"', "").replace("\\", "")
pathlib.Path("build/gen/ec/controltv/Config.java").write_text(
    "package ec.controltv;\nfinal class Config {\n"
    f'    static final String TV_IP = "{ip}";\n'
    f'    static final String TV_MAC = "{mac}";\n'
    f'    static final String TV_NOMBRE = "{nombre}";\n}}\n', encoding="utf-8")
PY
"$JDK/javac.exe" --release 8 -classpath "$JAR" -encoding UTF-8 -Xlint:-options \
  -d $OUT/classes $(find src $OUT/gen -name '*.java')

echo "3/6 dex"
"$BT/d8.bat" --release --min-api 26 --lib "$JAR" --output $OUT/dex $(find $OUT/classes -name '*.class')

echo "4/6 empaquetar"
python - <<'PY'
import zipfile, shutil
shutil.copy("build/base.apk", "build/unaligned.apk")
with zipfile.ZipFile("build/unaligned.apk", "a", zipfile.ZIP_DEFLATED) as z:
    z.write("build/dex/classes.dex", "classes.dex")
PY
"$BT/zipalign.exe" -f -p 4 $OUT/unaligned.apk $OUT/aligned.apk

echo "5/6 firma"
if [ ! -f $KS ]; then
  "$JDK/keytool.exe" -genkeypair -keystore $KS -alias controltv -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass $KS_PASS -keypass $KS_PASS -dname "CN=Control TV casero" >/dev/null
fi
"$BT/apksigner.bat" sign --ks $KS --ks-pass pass:$KS_PASS --out ../ControlTV.apk $OUT/aligned.apk

echo "6/6 verificar"
"$BT/apksigner.bat" verify ../ControlTV.apk && ls -la ../ControlTV.apk
