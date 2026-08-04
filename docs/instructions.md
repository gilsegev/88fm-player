Act as a Senior Android Developer. Create a minimal "Hello World" Android app in Kotlin that plays a single audio stream both on the phone and through Android Auto using AndroidX Media3 (`MediaLibraryService` and `ExoPlayer`).



\### Requirements:

1\. \*\*Target SDK \& Config\*\*:

&#x20;  - Modern Kotlin + Gradle setup (Min SDK 24, Target SDK 34).

&#x20;  - Dependencies needed: `androidx.media3:media3-exoplayer` and `androidx.media3:media3-session`.



2\. \*\*Hardcoded Media Item\*\*:

&#x20;  - Title: "Loop 88 Test Stream"

&#x20;  - Artist: "KAN 88"

&#x20;  - Stream URL: `https://traffic.omny.fm/d/clips/23f697a0-7e6a-4e96-a223-a82c00962b12/a888a279-9911-4085-9a92-ab3900a0c251/d24f4a07-fd81-4112-b862-b49900f8b418/audio.mp3?utm\_source=Podcast\&in\_playlist=425d386f-3564-4ec5-95d3-ab3900a0c251`



3\. \*\*Android Auto Media Service (`PlaybackService.kt`)\*\*:

&#x20;  - Extend `MediaLibraryService`.

&#x20;  - Initialize an `ExoPlayer` instance and wrap it in a `MediaLibrarySession`.

&#x20;  - Implement `onGetLibraryRoot` and `onGetChildren` so that Android Auto displays a single clickable track ("Loop 88 Test Stream") when opened on the car display.

&#x20;  - Automatically prepare and start playback when the track is selected.



4\. \*\*Phone UI (`MainActivity.kt`)\*\*:

&#x20;  - A super basic activity with a single Play/Pause toggle button to test phone-side playback easily.

&#x20;  - Bind to `PlaybackService` or connect via a `MediaController`.



5\. \*\*Android Auto Manifest Declarations\*\*:

&#x20;  - Include `<uses-permission android:name="android.permission.INTERNET"/>` and `<uses-permission android:name="android.permission.FOREGROUND\_SERVICE"/>`.

&#x20;  - Declare the service in `AndroidManifest.xml` with intent-filter `androidx.media3.session.MediaLibraryService`.

&#x20;  - Add `<meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive\_app\_desc"/>` in `<application>`.

&#x20;  - Create `res/xml/automotive\_app\_desc.xml` with `<automotiveApp><uses name="media"/></automotiveApp>`.



Provide the complete code for:

1\. `build.gradle.kts` (app module)

2\. `AndroidManifest.xml`

3\. `res/xml/automotive\_app\_desc.xml`

4\. `PlaybackService.kt`

5\. `MainActivity.kt`

