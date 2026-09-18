# Ubuntu for Android

Full Ubuntu (chroot via PRoot) running **inside the app** — no Termux, no
copy-pasting `ubuntu.sh`. The whole reference flow
([wahasa/Ubuntu](https://github.com/wahasa/Ubuntu): `Install/ubuntu.sh`,
`Desktop/de-*.sh`, `Apps/*.sh`, `Patch/*`, multi-`Distro`) is ported to
native Kotlin + Jetpack Compose with **Material 3 Expressive** and
**dynamic color** (Ubuntu-orange fallback on Android 10–11).

```
ketik plucky  ->  [Release card: plucky 25.04]
set user      ->  [Credentials form]
set pw        ->  [Credentials form]
bash ubuntu.sh->  [SetupOrchestrator: download -> extract -> provision -> apt -> DE -> done]
```

## Modules

| Module | Contents |
|---|---|
| `:app` | `MainActivity`, Compose navigation, dashboard, settings, apt UI, foreground service, JNI `pty.c` |
| `:core-ui` | M3 Expressive theme, dynamic colors + Ubuntu fallback palette, shared components |
| `:core-data` | models, release/app catalogs, DataStore repos, arch detection |
| `:core-rootfs` | HTTP resume downloader + SHA-256 verify, tar.gz/.xz extractor (`--link2symlink` semantics) |
| `:core-proot` | `ubuntu.sh` port: proot argv builder, PTY processes, session manager, apt runner, setup orchestrator, audio bridge, experimental root chroot, backup/restore |
| `:core-vnc` | TigerVNC (inside rootfs) controller + pure-Kotlin websockify bridge |
| `:feature-onboarding` | wizard: welcome → release → desktop → user/pass → progress/log |
| `:feature-terminal` | self-contained VT100/xterm emulator, canvas terminal, pinch-zoom, Termux-style extra-keys row |
| `:feature-desktop` | noVNC viewer (bundled assets) over 127.0.0.1 websockify |
| `:feature-appstore` | app grid (Chromium/Firefox/GIMP/Krita/VS Code/…), apt install + the reference fixes |

## Build

1. Android Studio (2025.1+) or SDK + JDK 17/21. `compileSdk/targetSdk = 36`, `minSdk = 29`.
2. **Bundle proot (required, GPL-2.0)** — put a statically built proot, renamed:
   ```
   app/src/main/jniLibs/arm64-v8a/libubuntu-proot.so     (arm64 build)
   app/src/main/jniLibs/armeabi-v7a/libubuntu-proot.so   (arm 32 build)
   ```
   The `*.so` rename keeps it in `nativeLibraryDir`, which stays executable on
   Android 10+ (the standard trick used by PRoot-Distro style apps).
   Get it from Termux's `proot` package (extract `bin/proot` from the .deb) or
   build upstream (proot-me/proot) — **for Android 15/16 you need 16 KB page
   alignment**: build with `LDFLAGS="-Wl,-z,max-page-size=16384"` and verify:
   ```
   llvm-readelf -l libubuntu-proot.so | grep Load   # Align must be 2**14 or larger
   ```
   Same rules apply to the optional `libubuntu-pulseaudio.so` (host-side
   PulseAudio with AAudio sink, like Termux's build) and any other binary.
3. **PTY helper (recommended):** install NDK r27+ in SDK Manager, then create
   an empty file `app/with-ndk`. Gradle then compiles `src/main/cpp/pty.c`
   (`-Wl,-z,max-page-size=16384` already set) into `libubuntu-pty.so` for
   real interactive terminals (vim/htop/nano). Without it the app falls back
   to pipe sessions.
4. `./gradlew :app:assembleDebug` → install the APK.

Already vendored in `app/src/main/assets`: **noVNC 1.6.0** (MPL-2.0,
`assets/novnc/`) and the reference app icons (`assets/appicons/`).

## Using

* Dashboard → **Install Ubuntu** → pick e.g. `plucky 25.04`. Default source is
  the prebuilt **[wahasa/Ubuntu `Rootfs` release](https://github.com/wahasa/Ubuntu/releases/tag/Rootfs)**
  (build 18012026): `ubuntu-<codename>-<arm64|armhf>-root.tar.xz` (~50 MB)
  with **pinned SHA-256 per file** compiled into the app. Switchable to
  Canonical OCI (`partner-images.canonical.com/oci/…/current/…-root.tar.gz`)
  or **local file import** (`ubuntu-plucky-arm64-root.tar.xz` via SAF) —
  `focal`/`bionic` have no release asset and fall back to OCI automatically.
* Choose XFCE/LXQt/LXDE/KDE or CLI-only, set username + password, watch the
  ported installer: extract → `etc/hostname|hosts|resolv.conf`, deb822
  `ubuntu.sources` (ports.ubuntu.com), `useradd`/`chpasswd`/sudoers,
  udisks2 postinst hack + `apt-mark hold`, `tigervnc-standalone-server`,
  `xstartup`, `vnc-start`/`vnc-stop`, TZ, audio.
* **Terminal** opens as your user (`su -`); open it as root from the card menu.
* **Desktop** starts `vncserver -localhost yes -SecurityTypes VncAuth :1`
  inside the container and connects noVNC through the in-app websockify
  bridge on `127.0.0.1:6080` (VNC password is regenerated per start and fed
  via URL — you never type it).
* **Apps** card grid installs via apt and applies the reference fixes:
  mozillateam PPA deb822 sources (firefox/thunderbird), Debian stable repo
  + `--no-sandbox` sed (chromium), latest VS Code .deb + patched
  `code.desktop`, LibreOffice `oosplash` patch + `/prod/version`.
* Multiple distros can coexist (`files/containers/<id>/rootfs`), with
  backup/restore (tar through proot), reset, custom bind mounts, and the
  `/sdcard` bridge.

## Android 10 → 16 compliance checklist

| Version | Handled |
|---|---|
| 10 (API 29) | `minSdk=29`; app-private `filesDir` only → no storage perms needed; `/sdcard` bind guarded by existence check; symlinks only on internal storage; exec from `nativeLibraryDir` (W^X safe) |
| 11–12 | scoped storage: all writes inside app storage; rootfs import via SAF (`content://`); optional `/storage/emulated/0` bind |
| 12 (31) | dynamic color `dynamicLight/DarkColorScheme()`; static Ubuntu palette fallback below |
| 13 (33) | `POST_NOTIFICATIONS` runtime request (used by session notification); per-app language (`locales_config.xml`, en/id) |
| 14 (34) | FGS type declared: `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` (`ServiceCompat.startForeground`); predictive back enabled |
| 15 (35) | edge-to-edge enforced → `enableEdgeToEdge()` + `imePadding()`; 16 KB page alignment for `.so` (`useLegacyPackaging=false`, NDK flags, verify prebuilts with readelf) |
| 16 (36) | `targetSdk=36`; regional/preferences neutral (TZ configurable); no restricted APIs (no `dladdr` tricks, no dex exec, no `SET_ENFORCE_STRICT_SCRIPT`) |

## Port map (reference → code)

| Reference | Ported to |
|---|---|
| `Install/ubuntu.sh` | `SetupOrchestrator`, `RootfsProvisioner`, `ProotCommandBuilder` (`--link2symlink --kill-on-exit --kernel-release=… -0 -r`, all `-b` binds incl. `binds/` dir support) |
| `Desktop/de-xfce|lxqt|lxde|kde.sh` | `DEScripts` (pkg sets, udisks2 hold, xstartup, vncstart/vnc-start/vnc-stop, lxpolkit rename) |
| `Apps/firefox.sh`, `chromium` | `AppFixes` mozillateam PPA + Debian repo (deb822 with embedded signing key) |
| `Apps/vscodefix.sh`, `libreofficefix.sh`, `Patch/code.desktop`, `user.js`, `oosplash` | `AppFixes` (fetches latest code deb / oosplash at install time) |
| `tigervnc` helper | `VncController` + `WebsockifyBridge` + bundled noVNC |
| `Distro/` multi-instance | `InstanceRepository`, `files/containers/<id>` |
| `.ubuntu` launcher env | `ProotCommandBuilder` env block (PATH/HOME/LANG/TMPDIR/PULSE_SERVER/MOZ_FAKE_NO_SANDBOX) |

## Licensing & distribution notes

* proot, pulseaudio, alsa — **GPL**: ship `LICENSE` files and honor source
  offers if you redistribute binaries; noVNC assets are MPL-2.0
  (`assets/novnc/LICENSE.txt`).
* Google Play flags apps that download and execute code — rootfs tarballs
  are *data*, but arbitrary-binary policies live in a gray zone. This project
  targets **sideload / F-Droid / GitHub releases**.
* Ubuntu, Canonical names/marks are trademarks of Canonical Ltd; use a
  non-branded icon/app name if you publish publicly.
