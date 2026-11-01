Overrides for jpackage on Linux. jpackage falls back to its own templates for anything not here.

Modula.desktop  The menu entry. jpackage installs it (postinst runs xdg-desktop-menu) because it
                is present here, as modula-Modula.desktop: package name, dash, launcher. It carries
                no comments because it is installed on users' machines, and jpackage substitutes its
                tokens inside comments too (one shipped reading "jpackage's Unknown token").

  StartupWMClass  The running window's real class, read off a live window with xprop rather than
                  assumed: JavaFX derives it from the module main class, not from the application
                  name. Without it a desktop cannot match the open window to this launcher, so the
                  dock shows a generic icon beside a correctly-iconned menu entry.

  Categories      Literal rather than jpackage's DEPLOY_BUNDLE_CATEGORY token, because the flag that
                  fills that in (--linux-menu-group) is Linux-only and would fail the macOS and
                  Windows builds outright.

  Icon            By name, io.github.adriandeleon.Modula, the icon finish-deb.sh installs into the
                  hicolor theme. APPLICATION_ICON is a path under /opt, and a software centre looks
                  an icon up in the theme, never at a path.

io.github.adriandeleon.Modula.metainfo.xml
                The AppStream listing GNOME Software and KDE Discover show for the installed app:
                name, icon, description, licence, homepage, screenshot and release notes. Its
                <pkgname> ties it to the deb, and its <launchable> names modula-Modula.desktop.

  <releases>      Written by hand. Add the version, date and a sentence of description when cutting
                  a release, or Software names the previous one as the latest (LinuxListingTest
                  fails until it matches the newest release in CHANGELOG.md).

control         The .deb's control file: jpackage's own template with these changed.

  Depends     Written out rather than left to jpackage, which copies the build host's package
              names. On a current host ALSA is libasound2t64 (Debian's 64-bit time_t rename), which
              does not exist on Debian 12 or Ubuntu 22.04, so a .deb built there would not install
              on either. libasound2t64 Provides libasound2, so the alternative satisfies both. The
              rest is the list jpackage computed on Debian 13 for this app (JavaFX and AWT's X11 and
              ALSA libraries); if a new dependency ever adds a library, add it here.

  Recommends  librtlsdr, for direct access to the dongle. A recommendation, not a dependency:
              without it Modula falls back to rtl_tcp, which is also how a dongle on another
              machine is used.

  Description An extended description under the summary, for apt show and package managers.

jpackage.args   Extra options for Linux installers; see the comments inside. --about-url fills the
                control file's Homepage.

signing-key.asc The public half of the release signing key, 8486A7AF4FC8FEE93D83CDB4EA5FA138C2C09C4A,
                shared with Editora, Torrenta and Termina. A tagged release refuses to sign with any
                other key. README, "Signing".

Beyond /opt
-----------
jpackage installs the application under /opt/modula and nothing anywhere else, and its deb bundler
has no hook for files outside it. scripts/finish-deb.sh, run by the dist profile straight after
jpackage, unpacks the finished deb and adds, as files the package owns:

  /usr/share/metainfo/io.github.adriandeleon.Modula.metainfo.xml
  /usr/share/icons/hicolor/<n>x<n>/apps/io.github.adriandeleon.Modula.png, every branding size,
      and scalable/apps/io.github.adriandeleon.Modula.svg
  /usr/share/doc/modula/copyright, in the Debian copyright format, generated from LICENSE
      (also written over jpackage's own /opt/modula/share/doc/copyright, which reads
      "License: Unknown")

then rewrites md5sums and Installed-Size and rebuilds it with the compression jpackage used. Owned
rather than copied in by postinst, because a software centre finds a listing's package by asking
dpkg who owns the metainfo, and dpkg removes owned files with the package.

scripts/check-linux-package.sh asserts all of this against the built deb, in CI and in the release,
because every failure above produces a package that installs and looks fine.

What GNOME Software shows where
-------------------------------
Two different pages, and only one of them reads the listing:

  - Double-clicking a downloaded .deb opens an install window built from what PackageKit reads out
    of the package: "modula" in lower case, the summary (used as the description too), the size and
    the Homepage. It reads no metainfo, icon or licence, so it shows a generic icon and "Unknown
    License" for every downloaded deb, ours or anyone's. Homepage is the only part a package can
    improve, which is why --about-url is set.
  - Once installed, the app's page (Installed -> Modula) is the listing: name, icon, MIT, homepage,
    screenshot and release notes, from the metainfo.

Check either from a shell:

  /usr/libexec/gnome-software-cmd filename-to-app "$PWD/target/dist/modula_<v>_amd64.deb" --show-results
  /usr/libexec/gnome-software-cmd installed --show-results --refine-flags=license,url,icon
