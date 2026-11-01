#!/usr/bin/env bash
# Adds to jpackage's finished .deb what GNOME Software and KDE Discover need to list Modula as an
# application rather than a bare package: the AppStream metainfo, the icon in the hicolor theme, and a
# machine-readable copyright. Run by the dist profile straight after jpackage, on Linux only.
#
# jpackage's deb bundler packages /opt/modula and nothing else, and offers no hook for files outside
# it, so the deb is unpacked, given them, and rebuilt. They are files the package owns rather than ones
# a postinst copies in: a software centre ties a listing to its package by asking dpkg who owns the
# metainfo, and dpkg removes owned files with the package.
#
#   /usr/share/metainfo/io.github.adriandeleon.Modula.metainfo.xml   the listing
#   /usr/share/icons/hicolor/<n>x<n>/apps/io.github.adriandeleon.Modula.png, and scalable/ the SVG
#                                                                     what the listing and the menu
#                                                                     entry name; a software centre
#                                                                     looks an icon up by name, never
#                                                                     at a path under /opt
#   /usr/share/doc/modula/copyright                                   the licence, where Debian's
#                                                                     tools look, in the copyright
#                                                                     format rather than pasted raw
#
# md5sums and Installed-Size describe the contents and are rewritten to match. The archive is rebuilt
# with the compression jpackage's own dpkg-deb chose, so the result installs wherever the original did.
#
# Needs dpkg-deb, ar, md5sum and du. scripts/check-linux-package.sh checks the result.
#
# Usage: finish-deb.sh <dir-holding-the-deb>
set -euo pipefail

dir="${1:?directory holding the deb required}"
root_dir="$(cd "$(dirname "$0")/.." && pwd)"
app_id=io.github.adriandeleon.Modula
metainfo="$app_id.metainfo.xml"

shopt -s nullglob
debs=("$dir"/modula_*.deb)
# An app-image build, or a type other than deb, leaves nothing to finish.
[ "${#debs[@]}" -gt 0 ] || { echo "[finish-deb] no modula_*.deb in $dir; nothing to do"; exit 0; }
[ "${#debs[@]}" = 1 ] || { echo "[finish-deb] more than one deb in $dir: ${debs[*]}" >&2; exit 1; }
deb="$(cd "$(dirname "${debs[0]}")" && pwd)/$(basename "${debs[0]}")"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
pkg="$work/pkg"

compression=$(ar t "$deb" | sed -n 's/^data\.tar\.\{0,1\}//p')
case "$compression" in
    xz | zst | gz) ;;
    '') compression=none ;;
    *) echo "[finish-deb] unexpected data member data.tar.$compression" >&2; exit 1 ;;
esac
[ "$compression" = zst ] && compression=zstd
[ "$compression" = gz ] && compression=gzip

dpkg-deb -R "$deb" "$pkg"

install -D -m 644 "$root_dir/packaging/linux/$metainfo" "$pkg/usr/share/metainfo/$metainfo"
for size in 16 24 32 48 64 128 256 512; do
    install -D -m 644 "$root_dir/branding/modula-icon-$size.png" \
        "$pkg/usr/share/icons/hicolor/${size}x${size}/apps/$app_id.png"
done
install -D -m 644 "$root_dir/branding/modula-icon.svg" "$pkg/usr/share/icons/hicolor/scalable/apps/$app_id.svg"

# The Debian copyright format, generated from LICENSE so the licence keeps one source.
license="$root_dir/LICENSE"
mkdir -p "$pkg/usr/share/doc/modula"
{
    echo "Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/"
    echo "Upstream-Name: Modula"
    echo "Source: https://github.com/adriandeleon/Modula"
    echo
    echo "Files: *"
    echo "Copyright: $(sed -n 's/^Copyright (c) //p' "$license" | head -1)"
    echo "License: MIT"
    echo "Comment: Third-party components and their licences are listed in"
    echo " /opt/modula/lib/NOTICE and /opt/modula/lib/THIRD-PARTY-LICENSES.txt; the"
    echo " Java runtime's own notices are in /opt/modula/lib/runtime/legal."
    echo
    echo "License: MIT"
    sed -e '1{/^MIT License$/d;}' "$license" | sed -e '1{/^$/d;}' -e 's/^$/./' -e 's/^/ /'
} > "$pkg/usr/share/doc/modula/copyright"
chmod 644 "$pkg/usr/share/doc/modula/copyright"
# jpackage writes its own under /opt, "License: Unknown" when it is not handed LICENSE raw.
[ -f "$pkg/opt/modula/share/doc/copyright" ] &&
    cp "$pkg/usr/share/doc/modula/copyright" "$pkg/opt/modula/share/doc/copyright"

(cd "$pkg" && find . -path ./DEBIAN -prune -o -type f -printf '%P\0' | LC_ALL=C sort -z |
    xargs -0 md5sum) > "$pkg/DEBIAN/md5sums"
size=$(du -sk --exclude=DEBIAN "$pkg" | cut -f1)
sed -i "s/^Installed-Size: .*/Installed-Size: $size/" "$pkg/DEBIAN/control"

dpkg-deb --root-owner-group -Z"$compression" --build "$pkg" "$deb"
echo "[finish-deb] $(basename "$deb"): metainfo, icons and copyright added"
