#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p work/audio-tests
javac -d work/audio-tests source-overrides/app/src/main/java/dev/codevibes/kotoba/probe/LiveAudioAdapter.java tests/LiveAudioAdapterTest.java
java -cp work/audio-tests dev.codevibes.kotoba.probe.LiveAudioAdapterTest
