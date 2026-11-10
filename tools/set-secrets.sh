#!/usr/bin/env bash
# One-time: hands the release key from ~/.config/antwort-radar to GitHub Actions, so tags build signed APKs.
set -euo pipefail
R=BrickyStudios/antwort-radar
base64 -w0 ~/.config/antwort-radar/release.jks | gh secret set KEYSTORE_BASE64 -R "$R"
set -a; . ~/.config/antwort-radar/signing.env; set +a
for k in SIGNING_KEY_ALIAS SIGNING_KEY_PASSWORD SIGNING_STORE_PASSWORD; do printf '%s' "${!k}" | gh secret set "$k" -R "$R"; done
gh secret list -R "$R"
