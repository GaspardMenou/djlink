#!/bin/zsh
cd "${0:A:h}"
if [[ ! -d "target/DJ Link.app" ]]; then
  python3 build-app.py || exit 1
fi
open "target/DJ Link.app"
