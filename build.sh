#!/bin/sh
set -eu
cd "$(dirname "$0")"
ANDROID_JAR=/opt/android-sdk/platforms/android-29/android.jar
D8_JAR=/opt/android-sdk/build-tools/35.0.1/lib/d8.jar
JAVA17=/usr/lib/jvm/java-17-openjdk-arm64/bin/java
JAVAC17=/usr/lib/jvm/java-17-openjdk-arm64/bin/javac

rm -rf build/gen build/classes build/dex build/apk
mkdir -p build/gen build/classes build/dex build/apk
/usr/bin/aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR"
"$JAVAC17" -source 11 -target 11 -cp "$ANDROID_JAR" -d build/classes $(find src build/gen -name '*.java' -print)
/usr/bin/jar cf build/classes.jar -C build/classes .
"$JAVA17" -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 26 --output build/dex build/classes.jar
/usr/bin/aapt package -f -M AndroidManifest.xml -S res -I "$ANDROID_JAR" -F build/apk/app-unsigned.apk
(cd build/dex && /usr/bin/aapt add ../apk/app-unsigned.apk classes.dex >/dev/null)
/usr/bin/zipalign -f 4 build/apk/app-unsigned.apk build/apk/app-aligned.apk
if [ ! -f build/debug.keystore ]; then
  /usr/lib/jvm/java-17-openjdk-arm64/bin/keytool -genkeypair -v -keystore build/debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=LipRead Lab,C=US"
fi
/usr/bin/apksigner sign --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android --out build/apk/LipReadLab.apk build/apk/app-aligned.apk
/usr/bin/apksigner verify --verbose build/apk/LipReadLab.apk
sha256sum build/apk/LipReadLab.apk
