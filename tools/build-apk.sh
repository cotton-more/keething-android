#!/bin/zsh
# Builds the release APK and signs it (SPEC §8): tools/build-apk.sh [--install] [--timings] [folder]
#   KEYSTORE   the signing keystore (default ~/.keystores/keething.jks). The default's password comes from the login
#              Keychain item "keething-apk-keystore" when there is one (made once with
#              `security add-generic-password -a "$USER" -s keething-apk-keystore -w`); else apksigner asks for it.
#   --install  also installs it with adb (the device in ANDROID_SERIAL, or the only one connected).
#   --timings  a measurement build, never one to ship: Log.i's timing lines kept (DEVELOPMENT.md "Measuring a large
#              database"); named -timings, and only installed, never left in a folder. Without it, keething.timings is
#              set false whatever a gradle.properties says.
#   folder     where the APK lands; else KEETHING_APK_DIR (a folder that syncs to the phone, say), else ~/Downloads.
# The APK is named Keething-<version>-<commit>.apk, -<commit>-dirty with uncommitted changes.
set -e
install=
timings=false
dir=${KEETHING_APK_DIR:-$HOME/Downloads}
for arg in "$@"; do
    if [[ $arg == --install ]]; then install=1; elif [[ $arg == --timings ]]; then timings=true; else dir=${arg:a}; fi
done
if [[ $timings == true ]]; then
    [[ -n $install ]] || { echo "A measurement build (--timings) is only installed: add --install."; exit 1 }
    dir=$(mktemp -d)
fi
if [[ ! -d $dir ]]; then
    echo "No folder $dir."
    exit 1
fi
cd "${0:A:h}/.."
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
keystore=${KEYSTORE:-$HOME/.keystores/keething.jks}
if [[ ! -f $keystore ]]; then
    echo "No keystore at $keystore (set KEYSTORE, or make one: see DEVELOPMENT.md \"The signing key\")."
    exit 1
fi
[[ -n $(git status --porcelain) ]] && echo "Note: the working tree has uncommitted changes; they are built too."

./gradlew -q assembleRelease -Pkeething.timings=$timings
tools=$(ls -d $ANDROID_HOME/build-tools/* | sort -V | tail -1)
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
# Uncommitted changes: -dirty, so it never takes the name of the commit's own build.
out=$dir/Keething-$version-$(git rev-parse --short HEAD)$([[ -z $(git status --porcelain) ]] || echo -dirty)$([[ $timings == false ]] || echo -timings).apk
aligned=$(mktemp -d)/aligned.apk
$tools/zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk $aligned
# The signed APK is the unsigned build and its signing block alone (task apk:reproduce, keething-spec
# app.fdroid-build): no v1 (JAR) signature, which minSdk 34 never reads and whose files would be entries of the archive,
# and the entries' alignment kept as built, not redone by apksigner.
# Through the environment, never the command line (ps shows arguments).
if [[ -z $KEYSTORE ]] && KEETHING_KS_PASS=$(security find-generic-password -a "$USER" -s keething-apk-keystore -w 2>/dev/null); then
    export KEETHING_KS_PASS
    $tools/apksigner sign --ks $keystore --ks-pass env:KEETHING_KS_PASS --alignment-preserved true --v1-signing-enabled false --v4-signing-enabled false --out $out $aligned
    unset KEETHING_KS_PASS
else
    $tools/apksigner sign --ks $keystore --alignment-preserved true --v1-signing-enabled false --v4-signing-enabled false --out $out $aligned
fi
/bin/rm -r ${aligned:h}
$tools/apksigner verify $out
echo "Built $out"
[[ -n $install ]] && adb install -r $out
[[ $timings == true ]] && /bin/rm -r $dir
exit 0
