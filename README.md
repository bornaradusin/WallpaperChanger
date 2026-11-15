# WallpaperChanger

An Android live wallpaper that puts up a new picture **or video** each time you lock the phone, like [Wallpaper Changer](https://play.google.com/store/apps/details?id=de.j4velin.wallpaperChanger), with videos.

- Folders or single files, shuffle without repeats or in order, subfolders
- Videos muted, loop or play once
- Material You colours follow the wallpaper
- Updates itself from GitHub releases

See the [wiki](../../wiki) for how to use it.

## Building

GitHub Actions builds a signed APK on every push to `main` and puts it in a draft release. The signing key comes from the repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`; without them it makes a debug build.
