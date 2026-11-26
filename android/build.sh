#!/bin/bash
# Builds ULTRON.apk with Ubuntu's Android tools.
set -e
cd "$(dirname "$0")"
J=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build gen && mkdir -p build/classes gen
aapt package -f -m -M AndroidManifest.xml -S res -A assets -I $J -F build/res.apk -J gen
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $J -Xlint:-options -nowarn -d build/classes gen/com/arsh/ultron/R.java $(find src -name "*.java") 2> build/javac.log || { grep -v "^Picked up" build/javac.log; echo "JAVA BUILD FAILED"; exit 1; }
dalvik-exchange --dex --min-sdk-version=26 --output=build/classes.dex build/classes 2> build/dx.log || { grep -v "^Picked up" build/dx.log; echo "DEX FAILED"; exit 1; }
if grep -q LambdaMetafactory build/classes.dex; then echo "LAMBDA FOUND - would crash on Android"; exit 1; fi
cp build/res.apk build/unsigned.apk
(cd build && zip -q unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f ultron.jks ]; then
  head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' > ultron.jks.pass
  keytool -genkeypair -keystore ultron.jks -storepass "$(cat ultron.jks.pass)" -keypass "$(cat ultron.jks.pass)" -alias ultron \
    -dname "CN=ULTRON, O=Arsh" -keyalg RSA -keysize 3072 -validity 20000 2>&1 | grep -v "^Picked up" || true
fi
apksigner sign --ks ultron.jks --ks-pass file:ultron.jks.pass --ks-key-alias ultron --out build/ULTRON.apk build/aligned.apk 2>&1 | grep -v "^Picked up" || true
apksigner verify -v build/ULTRON.apk 2>&1 | grep -v "^Picked up" | head -6
ls -la build/ULTRON.apk
