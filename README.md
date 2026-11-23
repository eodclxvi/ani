# Ani

Anime streaming app for Android: search, watch and track episodes. One app for **phones and Google TV / Android TV**.

![TV banner](app/src/main/res/drawable-xhdpi/tv_banner.png)

## Phone

Search, show pages with Sub/Dub and favourites, watch history, full-screen player.

## TV

On a TV, Ani appears in the TV app list with its own banner and opens a layout built for the remote
(`app/src/main/java/com/anistream/tv/`). If the phone screen is launched on a TV, it hands over to the TV layout.

- **Home** – Continue watching (with progress) and Favourites shelves.
- **Search** – press OK on the box to type, or use **Voice**.
- **Show page** – Resume / Play next, Sub or Dub, Favourite, numbered episode grid (ranges of 100 for long series).
- **Player** – OK pauses, ◀ ▶ skip 10 s (hold to go faster), ▼ for previous / next episode and subtitles, media keys work, next episode plays by itself, Home pauses.
- **History** – hold OK on a show to remove it.

Phone and TV share the same watch history and favourites code.

## Build and install

Needs JDK 21 and an Android SDK with platform 37.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=/opt/android-sdk ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk          # phone
adb -s <tv-address>:5555 install -r app/build/outputs/apk/release/app-release.apk   # TV
```

Builds are signed with `~/.android/debug.keystore`, so updates install over earlier copies. Use the release build
on a TV: debug Compose builds are slow on TV hardware.
