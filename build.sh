#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

rm -rf target dist
mkdir -p target/classes target/test-classes dist

echo "[1/4] Compiling main library classes (Java 11 bytecode target)..."
javac --release 11 -d target/classes \
    src/main/java/com/telephony/aec/*.java

echo "[2/4] Compiling unit and functional test suite..."
javac --release 11 -cp target/classes -d target/test-classes \
    src/test/java/com/telephony/aec/*.java

echo "[3/4] Running unit and functional test suite..."
java -cp target/test-classes:target/classes com.telephony.aec.AcousticEchoCancellerTest

echo "[4/4] Packaging distributable JAR and Sources JAR into dist/..."
jar --create --file dist/telephony-server-aec-1.0.0.jar \
    --main-class com.telephony.aec.TelephonyAecPipelineService \
    -C target/classes .

jar --create --file dist/telephony-server-aec-1.0.0-sources.jar \
    -C src/main/java .

echo "Build complete:"
ls -lh dist/
