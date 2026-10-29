#!/bin/zsh
# Builds the release APK and signs it (SPEC §8): tools/build-apk.sh [--install]
#   KEYSTORE   the signing keystore (default ~/.keystores/keething.jks). The default's password comes from the login
#              Keychain item "keething-apk-keystore" when there is one (made once with
#              `security add-generic-password -a "$USER" -s keething-apk-keystore -w`); else apksigner asks for it.
#   --install  also installs it with adb (the device in ANDROID_SERIAL, or the only one connected).
# The APK lands in ~/Downloads as Keething-<version>-<commit>.apk.
set -e
cd "${0:A:h}/.."
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
keystore=${KEYSTORE:-$HOME/.keystores/keething.jks}
if [[ ! -f $keystore ]]; then
    echo "No keystore at $keystore (set KEYSTORE, or make one: see DEVELOPMENT.md \"The signing key\")."
    exit 1
fi
[[ -n $(git status --porcelain) ]] && echo "Note: the working tree has uncommitted changes; they are built too."

./gradlew -q assembleRelease
tools=$(ls -d $ANDROID_HOME/build-tools/* | sort -V | tail -1)
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
out=~/Downloads/Keething-$version-$(git rev-parse --short HEAD).apk
aligned=$(mktemp -d)/aligned.apk
$tools/zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk $aligned
# Through the environment, never the command line (ps shows arguments).
if [[ -z $KEYSTORE ]] && KEETHING_KS_PASS=$(security find-generic-password -a "$USER" -s keething-apk-keystore -w 2>/dev/null); then
    export KEETHING_KS_PASS
    $tools/apksigner sign --ks $keystore --ks-pass env:KEETHING_KS_PASS --v4-signing-enabled false --out $out $aligned
    unset KEETHING_KS_PASS
else
    $tools/apksigner sign --ks $keystore --v4-signing-enabled false --out $out $aligned
fi
/bin/rm -r ${aligned:h}
$tools/apksigner verify $out
echo "Built $out"
[[ $1 == --install ]] && adb install -r $out
exit 0
