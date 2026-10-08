# Disposable emulator UI snapshots

Use only on a dedicated synthetic-data emulator. Never point this harness at a private phone or bypass protected screenshots. Standard uiautomator waits for accessibility idle; a frequently refreshing recorder may never become idle. This read-only shell helper instead obtains the current root immediately.

Example using JDK17 and the existing SDK:

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK}"
WORK=$(mktemp -d /tmp/hermes-verify-ui-XXXXXX)
mkdir -p "$WORK/classes" "$WORK/dex"
javac --release 8 -cp "$SDK/platforms/android-34/android.jar" \
  -d "$WORK/classes" tests/ui/UiSnapshot.java
"$SDK/build-tools/34.0.0/d8" --min-api 26 \
  --lib "$SDK/platforms/android-34/android.jar" --output "$WORK/dex" \
  "$WORK/classes/UiSnapshot.class"
jar cf "$WORK/ui-snapshot.jar" -C "$WORK/dex" classes.dex
/tmp/nottheomi-emulator/adb.sh push "$WORK/ui-snapshot.jar" /data/local/tmp/nottheomi-ui-snapshot.jar
```

Pass `--snapshot-jar /data/local/tmp/nottheomi-ui-snapshot.jar` to `scripts/emulator_smoke.py`. The wrapper is local runtime state, not a portable SDK component; configure a separately isolated emulator/server first. The smoke refuses a target whose `ro.kernel.qemu` property is not `1`, but an explicit test-only serial remains mandatory. Remove the temporary build directory after use and stop only the owned emulator/server.

For testing the exact signed release, sign the instrumentation APK with the same persistent signing identity as that release, then install the test APK on this disposable emulator. Never distribute the signed instrumentation APK, passwords or signing keys with the app.
