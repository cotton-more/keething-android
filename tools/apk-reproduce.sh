#!/bin/zsh
# Builds a signed release APK again from a fresh clone of its commit and compares the two, as F-Droid does before it
# ships our signature (keething-spec app.fdroid-build): the APK with its signing block taken out must be the fresh
# unsigned build byte for byte. The commit is read from the APK's own About (assets/build-info.properties).
# Usage: tools/apk-reproduce.sh <signed.apk>
set -e
[[ -f "$1" ]] || { echo "usage: $0 <signed.apk>"; exit 1 }
apk=${1:A}
cd "${0:A:h}/.."
info=$(unzip -p $apk assets/build-info.properties)
commit=${${(M)${(f)info}:#commit=*}#commit=}
[[ -n $commit ]] || { echo "No commit in $apk's build-info.properties."; exit 1 }
[[ $info == *dirty=false* ]] || { echo "$apk was built with uncommitted changes: nothing to reproduce."; exit 1 }
# A folder with a space, as a check that no path of this checkout gets into the build.
work=$(mktemp -d)/"fresh clone"
git clone -q --no-checkout . $work
git -C $work checkout -q $commit
echo "Building $commit in $work"
(cd $work && ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools} \
    ./gradlew -q --no-configuration-cache assembleRelease -Pkeething.timings=false)
python3 tools/apk-unsigned.py $apk ${work:h}/from-signed.apk
if cmp -s ${work:h}/from-signed.apk $work/app/build/outputs/apk/release/app-release-unsigned.apk; then
    echo "Reproduced: $apk is the fresh build of $commit and its signature."
    /bin/rm -rf ${work:h}
else
    echo "Not reproduced: the APKs differ. Both are kept in ${work:h} (from-signed.apk; the clone's build/outputs)."
    exit 1
fi
