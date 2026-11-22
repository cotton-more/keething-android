#!/bin/zsh
# Publishes a release on GitHub from the current branch (DEVELOPMENT.md "Releasing"): tools/release.sh [patch|minor|major]
# main holds the current version; an older major one is kept on a branch of its own and released from there.
# The version is app/build.gradle.kts's versionName. When its tag is published already, the next one is made: patch
# 1.2.3 -> 1.2.4, minor -> 1.3.0, major -> 2.0.0 (asked when not given), versionCode from it (major * 1000000 +
# minor * 1000 + patch: unique across branches, and growing with the version), and the changelog
# fastlane/metadata/android/en-US/changelogs/<versionCode>.txt started; it stops there to have the changelog written,
# and the next run goes on with that version. The version's change and its changelog are committed as
# "Version <version>"; anything else uncommitted stops it. Then it builds and signs the APK (tools/build-apk.sh),
# reproduces it from a fresh clone (tools/apk-reproduce.sh), and, after a typed "yes", tags HEAD v<version>, pushes
# the branch and the tag, and creates the GitHub release with Keething-<version>.apk (the name F-Droid's recipe
# downloads as Binaries), its .sha256 and the changelog; GitHub's "Latest" only when it is the highest version
# published. Nothing public happens before the "yes".
set -e
kind=$1
[[ -z $kind || $kind == (patch|minor|major) ]] || { echo "usage: $0 [patch|minor|major]"; exit 1 }
cd "${0:A:h}/.."
branch=$(git symbolic-ref --short -q HEAD) || { echo "Not on a branch: switch to main or to the old version's branch."; exit 1 }
gradle=app/build.gradle.kts
changelogs=fastlane/metadata/android/en-US/changelogs
marker="(Write what changed, then run task release again.)"
version=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' $gradle | head -1)
code=$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' $gradle | head -1)
[[ $version =~ '^[0-9]+\.[0-9]+\.[0-9]+$' && -n $code ]] || { echo "No versionName x.y.z or versionCode in $gradle."; exit 1 }
published() { git rev-parse -q --verify "refs/tags/v$1" >/dev/null || git ls-remote --exit-code --tags origin "v$1" >/dev/null }

if published $version; then
    [[ -z $(git status --porcelain) ]] || { echo "Uncommitted changes: commit them first."; exit 1 }
    if [[ -z $kind ]]; then
        print "v$version is published. The next one:"
        select kind in patch minor major; do [[ -n $kind ]] && break; done
    fi
    parts=(${(s:.:)version})
    case $kind in
        patch) next=$parts[1].$parts[2].$((parts[3] + 1)) ;;
        minor) next=$parts[1].$((parts[2] + 1)).0 ;;
        major) next=$((parts[1] + 1)).0.0 ;;
    esac
    published $next && { echo "v$next is published already."; exit 1 }
    parts=(${(s:.:)next})
    nextCode=$((parts[1] * 1000000 + parts[2] * 1000 + parts[3]))
    (( parts[2] < 1000 && parts[3] < 1000 && nextCode > code )) || { echo "versionCode $nextCode for $next is not above $code."; exit 1 }
    sed -i '' "s/^\( *\)versionName = \"$version\"$/\1versionName = \"$next\"/; s/^\( *\)versionCode = $code$/\1versionCode = $nextCode/" $gradle
    version=$next; code=$nextCode
    [[ -f $changelogs/$code.txt ]] || print -r -- "$marker" > $changelogs/$code.txt
    echo "Version $version (versionCode $code). Write $changelogs/$code.txt, then run task release again."
    exit 0
fi
[[ -z $kind ]] || echo "v$version is not published yet: releasing it ($kind ignored)."
changelog=$changelogs/$code.txt
[[ -f $changelog ]] || { echo "No $changelog (versionCode $code)."; exit 1 }
grep -qF -- "$marker" $changelog && { echo "$changelog is not written yet."; exit 1 }
dirty=$(git status --porcelain -- . ":!$gradle" ":!$changelog")
[[ -z $dirty ]] || { echo "Uncommitted changes besides the version and its changelog:"; echo $dirty; exit 1 }
if [[ -n $(git status --porcelain) ]]; then
    git add $gradle $changelog
    git commit -q -m "Version $version (versionCode $code)"
    echo "Committed: Version $version (versionCode $code)."
fi
tag=v$version
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

highest=$( (git tag -l 'v*'; print $tag) | sed 's/^v//' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)
latest=false; [[ $highest == $version ]] && latest=true
print "\nReady: $tag on $commit (branch $branch), ${apk:t} ($sha); GitHub's Latest: $latest.\n--- notes ---"; cat $work/notes.md; print -- "-------------"
read "answer?Tag, push $branch and the tag, and publish on GitHub? Type yes: "
[[ $answer == yes ]] || { echo "Nothing published. The APK is in $work."; exit 1 }
git tag -a $tag -m "Keething $version"
git push origin HEAD:refs/heads/$branch $tag
gh release create $tag $apk $apk.sha256 --verify-tag --latest=$latest --title "Keething $version" --notes-file $work/notes.md
echo "Published. A copy of the APK stays in $work."
