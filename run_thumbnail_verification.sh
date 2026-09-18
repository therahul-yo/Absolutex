#!/bin/bash
set -euo pipefail
passed=0
for i in $(seq 1 10); do
  ./gradlew :core:thumbnails:testDebugUnitTest --tests 'ThumbnailPipelineTest.*corrupt*' --tests 'ThumbnailPipelineTest.*poison*' --quiet 2>/dev/null
  code=$?
  if [ $code -eq 0 ]; then passed=$((passed + 1)); fi
  echo "Run $i: $code"
done
echo "Pass count: $passed / 10"
