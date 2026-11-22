#!/bin/zsh
# Publishes a release on GitHub (DEVELOPMENT.md "Releasing"): tools/release.sh <version>, e.g. 1.0.1.
# HEAD must be the release's commit with nothing uncommitted, its versionName <version> and its
# fastlane changelog (changelogs/<versionCode>.txt) written. It builds and signs the APK (tools/build-apk.sh),
# reproduces it from a fresh clone (tools/apk-reproduce.sh), then, after a typed "yes", tags HEAD v<version>, pushes
# the tag and creates the GitHub release with Keething-<version>.apk (the name F-Droid's recipe downloads as
# Binaries), its .sha256 and the changelog. Nothing public happens before the "yes".
set -e
version=$1
[[ $version =~ '^[0-9]+\.[0-9]+\.[0-9]+$' ]] || { echo "usage: $0 <version, e.g. 1.0.1>"; exit 1 }
cd "${0:A:h}/.."
tag=v$version
[[ -z $(git status --porcelain) ]] || { echo "Uncommitted changes: commit them first."; exit 1 }
gradle=app/build.gradle.kts
[[ $(grep -m1 'versionName = ' $gradle) == *"\"$version\""* ]] || { echo "$gradle's versionName is not $version."; exit 1 }
code=$(grep -m1 'versionCode = ' $gradle | grep -o '[0-9]\+')
changelog=fastlane/metadata/android/en-US/changelogs/$code.txt
[[ -f $changelog ]] || { echo "No $changelog (versionCode $code)."; exit 1 }
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && { echo "The tag $tag exists already."; exit 1 }
git ls-remote --exit-code --tags origin "$tag" >/dev/null && { echo "The tag $tag exists on origin already."; exit 1 }
commit=$(git rev-parse --short HEAD)

work=$(mktemp -d)
tools/build-apk.sh $work
built=$work/Keething-$version-$commit.apk
tools/apk-reproduce.sh $built
apk=$work/Keething-$version.apk
mv $built $apk
(cd $work && shasum -a 256 ${apk:t} > ${apk:t}.sha256)
sha=$(cut -d' ' -f1 $apk.sha256)
sdk=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
apksigner=$(ls -d $sdk/build-tools/*/apksigner | sort -V | tail -1)
cert=$($apksigner verify --print-certs $apk | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr a-f A-F | sed 's/../&:/g; s/:$//')
{
    cat $changelog
    print "\nAndroid 14 or later.\n\n**Verify the download**\n\n    SHA-256 (${apk:t}): $sha\n"
    print "Signing certificate SHA-256:\n\n    $cert\n"
    print "The build is reproducible: built from the tag \`$tag\`, the APK without its signature is the same byte for byte (DEVELOPMENT.md, \"Reproducing a release\")."
} > $work/notes.md

print "\nReady: $tag on $commit, ${apk:t} ($sha).\n--- notes ---"; cat $work/notes.md; print -- "-------------"
read "answer?Tag, push and publish on GitHub? Type yes: "
[[ $answer == yes ]] || { echo "Nothing published. The APK is in $work."; exit 1 }
git tag -a $tag -m "Keething $version"
git push origin $tag
gh release create $tag $apk $apk.sha256 --verify-tag --title "Keething $version" --notes-file $work/notes.md
echo "Published. A copy of the APK stays in $work."
