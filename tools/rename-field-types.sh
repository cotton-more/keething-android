#!/bin/zsh
# The field types' key under the apps' former name (KeePassBar.FieldTypes) renamed to Keething.FieldTypes in every
# entry and history item (core/vault RenameFieldTypes). Reads the database, writes a NEW file and checks it with
# KeePassXC; asks for the password without echo. Prints counts, never a value.
# Usage: tools/rename-field-types.sh <database.kdbx> <new.kdbx>
set -e
[[ -f "$1" && -n "$2" ]] || { echo "usage: $0 <database.kdbx> <new.kdbx>"; exit 1 }
[[ ! -e "$2" ]] || { echo "$2 exists: give a new file"; exit 1 }
database=${1:A}
output=${2:A}
cd "${0:A:h}/.."
printf 'Master password: '
read -rs password
echo
# A pipe from the built-in print: a here-string would go through a temporary file.
print -r -- "$password" | ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools} \
    ./gradlew -q --console=plain :core:vault:renameFieldTypes -Pdatabase="$database" -Poutput="$output"
unset password
