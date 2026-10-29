#!/bin/zsh
# The phone's writing tried on a COPY of a database, KeePass's schema included (core/vault KdbxProbe). Asks for the
# password without echo and passes it on standard input; prints counts, times and differences, never a value.
# Usage: tools/kdbx-probe.sh <copy.kdbx>
set -e
[[ -f "$1" ]] || { echo "usage: $0 <copy.kdbx>"; exit 1 }
copy=${1:A}
cd "${0:A:h}/.."
printf 'Master password: '
read -rs password
echo
# A pipe from the built-in print: a here-string would go through a temporary file.
print -r -- "$password" | ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools} \
    ./gradlew -q --console=plain :core:vault:probe -Pdatabase="$copy"
unset password
