Overrides for jpackage on Linux. jpackage falls back to its own templates for anything not here.

Modula.desktop  The menu entry. jpackage installs it (postinst runs xdg-desktop-menu) because it
                is present here; see the comments inside for StartupWMClass and Categories.

control         The .deb's control file: jpackage's own template with two lines changed.

  Depends     Written out rather than left to jpackage, which copies the build host's package
              names. On a current host ALSA is libasound2t64 (Debian's 64-bit time_t rename), which
              does not exist on Debian 12 or Ubuntu 22.04, so a .deb built there would not install
              on either. libasound2t64 Provides libasound2, so the alternative satisfies both. The
              rest is the list jpackage computed on Debian 13 for this app (JavaFX and AWT's X11 and
              ALSA libraries); if a new dependency ever adds a library, add it here.

  Recommends  librtlsdr, for direct access to the dongle. A recommendation, not a dependency:
              without it Modula falls back to rtl_tcp, which is also how a dongle on another
              machine is used.

jpackage.args   Extra options for Linux installers; see the comments inside.
