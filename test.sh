#!/bin/sh
set -eu
cd "$(dirname "$0")"
rm -rf build/test-classes
mkdir -p build/test-classes
/usr/lib/jvm/java-17-openjdk-arm64/bin/javac -d build/test-classes \
  src/com/nous/lipread/EndpointValidator.java \
  src/com/nous/lipread/TranscriptionResponse.java \
  src/com/nous/lipread/GeneratedAudio.java \
  src/com/nous/lipread/VideoUploader.java \
  tests/com/nous/lipread/*.java
for test in EndpointValidatorTest TranscriptionResponseTest GeneratedAudioTest VideoUploaderTest; do
  /usr/lib/jvm/java-17-openjdk-arm64/bin/java -cp build/test-classes "com.nous.lipread.$test"
done
python3 -m unittest backend.test_media_validation backend.test_upload_guard
