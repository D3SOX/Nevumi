<h1><img src="packaging/nevumi.svg" alt="" width="40" height="40" align="left">Nevumi</h1>

<p>
  <a href="https://github.com/D3SOX/Nevumi/releases">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://nevumi.app/badges/github-dark.svg">
      <img src="https://nevumi.app/badges/github-light.svg" alt="Download Nevumi from GitHub Releases" width="158" height="52">
    </picture>
  </a>&nbsp;
  <a href="https://fdroid.link/#https://nevumi.app/fdroid/repo?fingerprint=FDEBFCB9DD790730FE607ACB8340588A839C44D9EA5BA6671AB3FAB83952F94B">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://nevumi.app/badges/fdroid-dark.svg">
      <img src="https://nevumi.app/badges/fdroid-light.svg" alt="Add Nevumi&#x27;s F-Droid repository" width="158" height="52">
    </picture>
  </a>&nbsp;
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22me.d3sox.nevumi%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FD3SOX%2FNevumi%22%2C%22author%22%3A%22D3SOX%22%2C%22name%22%3A%22Nevumi%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%7D%22%7D">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://nevumi.app/badges/obtainium-dark.svg">
      <img src="https://nevumi.app/badges/obtainium-light.svg" alt="Install Nevumi with Obtainium" width="158" height="52">
    </picture>
  </a>&nbsp;
  <a href="#arch-linux">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://nevumi.app/badges/archlinux-dark.svg">
      <img src="https://nevumi.app/badges/archlinux-light.svg" alt="Add Nevumi&#x27;s Arch Linux repository" width="158" height="52">
    </picture>
  </a>
</p>

Nevumi is a music player for Android and Linux, built with Kotlin and Compose. It plays local files and connects to Navidrome and Subsonic-compatible servers.

Nevumi is in alpha. Features and file formats may change, and bugs are expected. Keep backups of your music files.

I started it because I couldn't find a music player with all the features I wanted. Namida came closest, but I didn't like its license. Nevumi is an independent implementation with a Material You interface, focused on local music and music servers.

## What it does

- **Library.** Browse songs, albums, artists, genres and folders, and search everything. You choose the library tabs and Home sections.
- **Playlists.** Create regular, smart and M3U playlists, sort them into folders and sync them with your music server.
- **Queue.** Up next is a separate queue. Save queues and switch between them.
- **Playback.** Nevumi has gapless playback, crossfade, speed and pitch control, an equalizer with AutoEQ headphone profiles and a sleep timer.
- **Lyrics.** Nevumi shows synced and plain lyrics from tags, `.lrc` files and your music server. It can also [look them up online](#online-lyrics).
- **Waveforms.** Nevumi draws each song's waveform from its audio.
- **Visualizer.** Nevumi plays MilkDrop visualizations through projectM and ships with over 10,000 presets.
- **Offline.** Download music from your server. Local and server copies of the same song show up as one song.
- **Discovery.** Artist pages show the biography, similar artists and top songs. Play similar songs from any server song or artist.
- **New releases.** Follow artists, or import them from Last.fm or a MusicBrainz collection. Home lists their recent and upcoming releases, and Nevumi can notify you about new ones. It contacts MusicBrainz only after you follow or import an artist.
- **SoundCloud.** Search and download songs through your own [sc-gate-dl](https://github.com/D3SOX/sc-gate-dl) server. Nevumi never contacts SoundCloud.
- **YouTube.** Search YouTube Music for songs, artists, albums and videos, or paste a link. An artist page lists the same shelves as on YouTube Music, such as top songs, albums, singles, videos and similar artists. Albums and playlists open as their own pages. Stream a result or save it as an M4A file after checking its tags. Nevumi contacts YouTube from your device through [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) and YouTube Music's web API, only after you turn it on in Settings.
- **Tag editing.** Edit tags and artwork of local files, or of your Navidrome files over SFTP. Look up metadata on MusicBrainz.
- **Scrobbling.** Nevumi keeps a listening history and scrobbles to your music server, Last.fm or ListenBrainz.
- **Tailscale.** The built-in client reaches servers on your tailnet without the Tailscale app or a VPN. Headscale works too.
- **Device sync.** Pair devices with a QR code to sync playlists, favorites, ratings, history and saved queues.
- **Backup.** Export settings, playlists, favorites, history and connection profiles to one file and restore it on another device. Backups never include passwords or keys.
- **Themes.** Pick light, dark or system mode, and system, artwork-based or custom colors.
- **Linux.** Nevumi has MPRIS media controls and an optional tray icon that keeps music playing after you close the window.
- **Android.** Nevumi has a home screen widget and a quick settings tile.

## Screenshots

These Android emulator screenshots use a fictional library.

| Home | Now playing | Queue | Audio effects |
| --- | --- | --- | --- |
| <img src="packaging/screenshots/home.png" alt="Home with favorite mixes and recent albums" width="220"> | <img src="packaging/screenshots/now-playing.png" alt="Now playing with artwork, waveform and playback controls" width="220"> | <img src="packaging/screenshots/queue.png" alt="Queue with played history and Up next" width="220"> | <img src="packaging/screenshots/audio-effects.png" alt="Audio effects with headphone profiles and equalizer controls" width="220"> |

## Platforms and servers

Android requires Android 12 or newer. APKs include arm64 and x86_64 native libraries. Linux packages target x86_64. MilkDrop needs OpenGL 3.3 on Linux or OpenGL ES 3.0 on Android.

The client uses the Subsonic REST API with token authentication, starting at API 1.13.0. Library discovery supports empty-search enumeration, album pagination and directory browsing. Streaming, downloads, playlists, favorites, ratings and scrobbling work through that API. Navidrome's native API is only used for remote file editing over SFTP and for uploading playlist covers. It signs in with the account password instead of a token. Other servers keep playlist covers in the app.

Linux MilkDrop monitors Nevumi's audio stream through PulseAudio or PipeWire's PulseAudio service. It does not record the microphone or the mixed audio from other applications. Android feeds it directly from the playback pipeline.

## Install

The [release workflows](https://github.com/D3SOX/Nevumi/actions) publish APKs, Arch packages and AppImages on [GitHub Releases](https://github.com/D3SOX/Nevumi/releases).

Nevumi's signed Android and Arch repositories run on GitHub Pages at [nevumi.app](https://nevumi.app).

### Android

Download an APK from [GitHub Releases](https://github.com/D3SOX/Nevumi/releases), [install with Obtainium][obtainium-install], or add [Nevumi's F-Droid repository][fdroid-repository]. The Obtainium link includes alpha releases and excludes development builds.

Scan this QR code in F-Droid to add the repository with its signing fingerprint:

<p align="center">
<a href="https://fdroid.link/#https://nevumi.app/fdroid/repo?fingerprint=FDEBFCB9DD790730FE607ACB8340588A839C44D9EA5BA6671AB3FAB83952F94B">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="packaging/fdroid-qr-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="packaging/fdroid-qr-light.png">
    <img src="packaging/fdroid-qr-light.png" alt="QR code for Nevumi's F-Droid repository, with the Nevumi logo" width="280" height="280">
  </picture>
</a>
</p>

To add it manually in F-Droid, paste this into the repository field:

```text
https://nevumi.app/fdroid/repo?fingerprint=FDEBFCB9DD790730FE607ACB8340588A839C44D9EA5BA6671AB3FAB83952F94B
```

GitHub, Obtainium and this F-Droid repository distribute the same release APK and receive updates signed by the same app key. This is Nevumi's own repository; it is not a listing in the main f-droid.org repository.

### Arch Linux

Download the repository signing key in a terminal and check its fingerprint:

```sh
curl -fsSL -o nevumi.asc https://nevumi.app/nevumi.asc
gpg --show-keys --fingerprint nevumi.asc
```

It must match:

```text
5948 F17F 1611 CC2B 995B C612 1F0D 5421 26D5 14AB
```

```sh
sudo pacman-key --add nevumi.asc
sudo pacman-key --lsign-key 5948F17F1611CC2B995BC6121F0D542126D514AB
```

Add the repository with this terminal command. It skips the step when pacman already has a `[nevumi]` repository:

```sh
sudo pacman-conf --repo-list | grep -qxF 'nevumi' ||
  printf '%s\n' '' '[nevumi]' 'SigLevel = Required DatabaseRequired' \
    'Server = https://nevumi.app/arch/$arch' |
  sudo tee -a /etc/pacman.conf >/dev/null
```

Install Nevumi, or update it with the rest of your system:

```sh
sudo pacman -Syu nevumi
```

The packages and repository database are signed. The public key is also available in [packaging/nevumi.asc](packaging/nevumi.asc).

You can install a downloaded Arch package directly:

```sh
sudo pacman -U ./nevumi-0.20.0-1-x86_64.pkg.tar.zst
```

### AppImage

For an AppImage:

```sh
chmod +x ./nevumi-0.20.0-x86_64.AppImage
./nevumi-0.20.0-x86_64.AppImage
```

If FUSE is unavailable, run it with `APPIMAGE_EXTRACT_AND_RUN=1`. AppImages bundle the JVM, mpv, FFmpeg and the visualizer. The host supplies its C and C++ runtimes and graphics drivers. CI builds them on Ubuntu 24.04.

On Linux, a Secret Service provider, such as KDE's wallet service, stores server credentials.

Development APKs install separately as **Nevumi dev** and use the public development key in `packaging/development.keystore`. Linux development packages replace Nevumi and use the same local data directory.

## Online lyrics

Online lyrics lookups are off by default. Enable them in Settings to search LRCLIB. NetEase Cloud Music has its own setting. When it's on, Nevumi searches both services at once and uses NetEase's lyrics when they're timed more precisely than LRCLIB's, for example word by word. NetEase receives the song's artist and title through undocumented web-player endpoints. These requests use no account, API key or paid subscription, but access can fail or require verification.

Each NetEase lookup makes one search request and up to three lyric requests, with a ten-second timeout for the whole lookup. No published quota or third-party lyrics API agreement has been confirmed. Nevumi caches successful results in its local library database and reuses them without another online lookup. NetEase results show "Lyrics from NetEase Cloud Music".

NetEase's permission for third-party lyric display, caching and redistribution has not been confirmed. Anonymous access does not establish permission to reuse lyrics, and attribution does not grant those rights.

## Development

Build, test and release instructions are in [DEVELOPMENT.md](DEVELOPMENT.md).

## License and credits

Nevumi uses [AGPL-3.0-only](LICENSE). Namida inspired the feature requirements. Nevumi does not include its implementation or assets.

The built-in Tailscale client uses [tsnet](https://tailscale.com/kb/1244/tsnet), under BSD-3-Clause, built without log uploads. Its Go dependencies' license notices ship with the Linux libraries and inside the APK.

MilkDrop rendering uses [projectM](https://github.com/projectM-visualizer/projectm/tree/4967dc003e5206d7df3ff447287f7609c47d2343), under LGPL-2.1-or-later. The desktop window follows the approach used by [Spotifast](https://github.com/crmne/spotifast). Third-party license notices ship with the native libraries. Run `python scripts/package-native-sources.py --output .test-environment/release-sources` to download the exact projectM, evaluator and mpv sources and copy Nevumi's projectM patch. The source URLs and checksums are pinned in [native/CMakeLists.txt](native/CMakeLists.txt) and [scripts/build_mpv.py](scripts/build_mpv.py).

The bundled presets and textures retain their upstream authorship and notices, including the [preset pack's distribution notice](https://github.com/projectM-visualizer/presets-cream-of-the-crop/blob/0180df21f5e0bd39b9060cc5de420ed2f1f9e509/LICENSE.md). `python scripts/package-milkdrop-presets.py` reproduces the library from checksum-verified source archives. Each app unpacks the library on first use and reuses it afterward.

The README badges use [Simple Icons](https://simpleicons.org/) and [Roboto](https://github.com/googlefonts/roboto-classic) lettering rendered as SVG paths.

[fdroid-repository]: https://fdroid.link/#https://nevumi.app/fdroid/repo?fingerprint=FDEBFCB9DD790730FE607ACB8340588A839C44D9EA5BA6671AB3FAB83952F94B
[obtainium-install]: https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22me.d3sox.nevumi%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FD3SOX%2FNevumi%22%2C%22author%22%3A%22D3SOX%22%2C%22name%22%3A%22Nevumi%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%7D%22%7D
