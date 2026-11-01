#!/usr/bin/env bash
# Checks the built .deb against what it must contain, as shipped rather than as written in
# packaging/linux: every failure below produces a package that builds, installs and looks fine, and
# shows up only as a bare listing in a software centre or a package that will not install elsewhere.
# Run by CI on every push and by the release before anything is staged.
#
# Needs dpkg-deb, md5sum and desktop-file-validate; appstreamcli if installed.
#
# Usage: check-linux-package.sh <dir-holding-the-deb>
set -euo pipefail

dir="${1:?directory holding the deb required}"
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail() { echo "::error::$*"; exit 1; }

# Lists go to files rather than being piped into grep -q: grep -q exits at the first match, which
# closes the pipe under dpkg-deb, and pipefail then reports the whole pipeline as failed.
has() { grep -q -- "$2" "$1"; }

app_id=io.github.adriandeleon.Modula
metainfo="/usr/share/metainfo/$app_id.metainfo.xml"
hicolor="/usr/share/icons/hicolor/512x512/apps/$app_id.png"
homepage=$(sed -n 's:.*<modula.homepage>\(.*\)</modula.homepage>.*:\1:p' "$root_dir/pom.xml")
[ -n "$homepage" ] || fail "no modula.homepage in pom.xml"

shopt -s nullglob
debs=("$dir"/*.deb)
[ "${#debs[@]}" = 1 ] || fail "expected one deb in $dir, found ${#debs[@]}"
deb="${debs[0]}"
echo "=== $deb"
dpkg-deb --info "$deb"

dpkg-deb --contents "$deb" > "$work/contents.txt"
dpkg-deb --field "$deb" Depends > "$work/depends.txt"
dpkg-deb --field "$deb" Description > "$work/description.txt"

# What GNOME Software's window for a downloaded .deb shows: it reads these fields and nothing else.
[ "$(dpkg-deb --field "$deb" Homepage)" = "$homepage" ] ||
    fail "the deb's Homepage is not $homepage, so Software's Project Website goes nowhere"
[ "$(wc -l < "$work/description.txt")" -gt 1 ] ||
    fail "the deb has no extended description, so apt show and package managers have only the summary"
case "$(dpkg-deb --field "$deb" Maintainer)" in
    *"<Unknown>"* | "") fail "the deb's Maintainer has no address" ;;
esac
if has "$work/description.txt" "APPLICATION_"; then fail "an APPLICATION_* placeholder in the description"; fi

# Files of the package's own (scripts/finish-deb.sh): Software finds a listing's package by asking
# dpkg who owns the metainfo.
for path in "$metainfo" "$hicolor" "/usr/share/icons/hicolor/scalable/apps/$app_id.svg" \
    /usr/share/doc/modula/copyright /opt/modula/lib/LICENSE /opt/modula/lib/NOTICE; do
    has "$work/contents.txt" " \.$path$" || fail "the deb does not own $path"
done

# A t64-renamed package on its own exists only on Debian 13+ and Ubuntu 24.04+, and makes the
# package uninstallable on Debian 12 and Ubuntu 22.04. Allowed only as one side of an alternative.
tr ',' '\n' < "$work/depends.txt" | sed 's/^ *//; s/ *$//' > "$work/depends-list.txt"
if grep -E 't64( |\(|$)' "$work/depends-list.txt" | grep -v '|' > "$work/t64.txt"; then
    fail "the deb depends on a t64-only package with no fallback: $(tr '\n' ' ' < "$work/t64.txt")"
fi
dpkg-deb --field "$deb" Recommends > "$work/recommends.txt"
has "$work/recommends.txt" "librtlsdr" || fail "the deb no longer recommends librtlsdr"

dpkg-deb -R "$deb" "$work/pkg"
pkg="$work/pkg"
(cd "$pkg" && md5sum --quiet --check DEBIAN/md5sums) || fail "the deb's md5sums do not match its files"

entry=$(find "$pkg/opt/modula/lib" -maxdepth 1 -name '*.desktop' -print -quit)
[ -n "$entry" ] || fail "the deb installs no desktop entry"
echo "--- shipped desktop entry ---"; cat "$entry"
desktop-file-validate "$entry"
has "$entry" "^StartupWMClass=com.modula.ModulaApp$" ||
    fail "no StartupWMClass: the override was ignored, as it is when misnamed"
has "$entry" "^Icon=$app_id$" || fail "the desktop entry's Icon is not $app_id"
if has "$entry" "APPLICATION_"; then fail "an APPLICATION_* placeholder in the desktop entry was not substituted"; fi
if has "$entry" "^#"; then fail "the desktop entry ships comments; jpackage substitutes tokens inside them"; fi
has "$pkg/DEBIAN/postinst" "xdg-desktop-menu install /opt/modula/lib/$(basename "$entry")" ||
    fail "postinst does not install $(basename "$entry")"

has "$pkg$metainfo" "<pkgname>modula</pkgname>" || fail "the metainfo names no package"
has "$pkg$metainfo" "<launchable type=\"desktop-id\">$(basename "$entry")</launchable>" ||
    fail "the metainfo's launchable is not $(basename "$entry"), the id xdg-desktop-menu installs"
if command -v appstreamcli > /dev/null 2>&1; then appstreamcli validate --no-net "$pkg$metainfo"; fi

for shipped in "$pkg/opt/modula/lib/Modula.png" "$pkg$hicolor"; do
    cmp -s "$shipped" "$root_dir/branding/modula-icon-512.png" ||
        fail "${shipped#"$pkg"} is not branding/modula-icon-512.png"
done

for copyright in "$pkg/usr/share/doc/modula/copyright" "$pkg"/opt/modula/share/doc/copyright; do
    [ -f "$copyright" ] || continue
    has "$copyright" "^License: MIT$" || fail "${copyright#"$pkg"} does not name the licence"
    if has "$copyright" "^License: Unknown"; then fail "${copyright#"$pkg"} reads License: Unknown"; fi
    has "$copyright" "^Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/$" ||
        fail "${copyright#"$pkg"} is not in the copyright format"
done

echo "deb: OK"
