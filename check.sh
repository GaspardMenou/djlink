#!/bin/sh
set -eu
cd "$(dirname "$0")"
python3 build.py
mkdir -p target/checks
javac --release 17 -encoding UTF-8 -cp target/djlink-1.0.0.jar -d target/checks src/test/java/local/djlink/SelfCheck.java
java -ea -cp target/checks:target/djlink-1.0.0.jar local.djlink.SelfCheck
if command -v node >/dev/null 2>&1; then node --check src/main/resources/web/app.js; node src/test/motion-check.js; fi
