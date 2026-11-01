#!/usr/bin/env bash
# Signs the .deb in each directory with the release's OpenPGP key, in place, and writes a detached
# signature beside it. Run by the release on the finished package, and by CI with a throw-away key so a
# broken signing step is found on a push rather than on release day.
#
# Two signatures, because they are checked by different things:
#   embedded   an `_gpgorigin` member, which debsig-verify checks. apt and dpkg check nothing about
#              a .deb that was downloaded rather than fetched from a repository.
#   detached   an armoured `<file>.asc`: `gpg --verify <file>.asc <file>`. What a user downloading
#              from the release page can actually check.
#
# Signing happens in a job of its own rather than on the runner that builds, because that runner
# executes whatever Maven resolves; the key is never on it.
#
# The key comes from the environment: MODULA_GPG_KEY, an ASCII-armoured secret key, and
# MODULA_GPG_PASSPHRASE if it has one. Its public half is written to <public-key-out>. The release
# sets MODULA_GPG_EXPECTED_KEY to the committed packaging/linux/signing-key.asc, and the key must be
# that one: a release signed by any other key would verify against the key published beside it and
# against nothing else.
#
# The deb is signed here rather than by debsigs, which does the same thing but knows only gzip
# members: given dpkg-deb's control.tar.xz and data.tar.xz it found neither, signed debian-binary
# alone, and exited 0. The format is debsigs': a detached signature over the three members dpkg
# reads, concatenated in archive order, appended as a member named `_gpgorigin`.
#
# Needs gpg and ar.
#
# Usage: sign-linux-packages.sh <public-key-out> <dir>...
set -euo pipefail

public_out="${1:?public key output required}"
shift
[ $# -gt 0 ] || { echo "usage: $0 <public-key-out> <dir>..." >&2; exit 2; }
[ -n "${MODULA_GPG_KEY:-}" ] || { echo "::error::MODULA_GPG_KEY is not set"; exit 1; }

GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
trap 'gpgconf --kill gpg-agent 2>/dev/null || true; rm -rf "$GNUPGHOME"' EXIT

# Every gpg below reads this. Loopback lets the passphrase come from the file rather than from a
# pinentry no runner can answer.
{
    echo "batch"
    echo "no-tty"
    echo "pinentry-mode loopback"
} > "$GNUPGHOME/gpg.conf"
if [ -n "${MODULA_GPG_PASSPHRASE:-}" ]; then
    printf '%s' "$MODULA_GPG_PASSPHRASE" > "$GNUPGHOME/passphrase"
    echo "passphrase-file $GNUPGHOME/passphrase" >> "$GNUPGHOME/gpg.conf"
fi

printf '%s\n' "$MODULA_GPG_KEY" | gpg --quiet --import
fpr=$(gpg --with-colons --list-secret-keys | awk -F: '$1 == "fpr" { print $10; exit }')
[ -n "$fpr" ] || { echo "::error::MODULA_GPG_KEY holds no secret key"; exit 1; }
echo "Signing with $fpr"

expected="${MODULA_GPG_EXPECTED_KEY:-}"
if [ -n "$expected" ]; then
    [ -f "$expected" ] || { echo "::error::no public key at $expected to check MODULA_GPG_KEY against"; exit 1; }
    want=$(gpg --with-colons --import-options show-only --import "$expected" |
        awk -F: '$1 == "fpr" { print $10; exit }')
    [ "$want" = "$fpr" ] || { echo "::error::MODULA_GPG_KEY is $fpr, but $expected is $want"; exit 1; }
fi
gpg --armor --export "$fpr" > "$public_out"

sign_deb() {
    local deb="$1" members=() member
    rm -rf "$GNUPGHOME/deb" && mkdir "$GNUPGHOME/deb"
    (cd "$GNUPGHOME/deb" && ar x "$deb")
    while read -r member; do
        case "$member" in
            debian-binary|control.tar*|data.tar*) members+=("$GNUPGHOME/deb/$member") ;;
            _gpgorigin) echo "::error::$(basename "$deb") is already signed"; exit 1 ;;
        esac
    done < <(ar t "$deb")
    [ "${#members[@]}" = 3 ] || { echo "::error::$(basename "$deb") does not have the three members of a deb"; exit 1; }
    cat "${members[@]}" | gpg --detach-sign --local-user "$fpr" --output "$GNUPGHOME/deb/_gpgorigin"
    ar q "$deb" "$GNUPGHOME/deb/_gpgorigin"
}

shopt -s nullglob
signed=0
for dir in "$@"; do
    # Absolute: the deb is unpacked from inside a directory of its own.
    dir="$(cd "$dir" && pwd)"
    for deb in "$dir"/*.deb; do
        sign_deb "$deb"
    done
    # After the embedded signature, which changes the file it is added to.
    for file in "$dir"/*.deb; do
        gpg --armor --detach-sign --local-user "$fpr" --output "$file.asc" "$file"
        echo "signed $(basename "$file")"
        signed=$((signed + 1))
    done
done
[ "$signed" -gt 0 ] || { echo "::error::nothing to sign in $*"; exit 1; }
