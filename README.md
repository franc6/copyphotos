# CopyPhotos

Two native apps, one for iOS and one for Android, that **copy** photos and videos from the device to an SMB network share and check every copy.

This is a copy tool, not a sync tool and not a backup tool:

- Deleting files on the destination never deletes anything on the device.
- Deleting photos on the device never deletes anything on the destination.
- The app never overwrites or removes existing files on the share. If the name is taken by a different file, the new file gets `_1`, `_2`, … appended to the name part (`IMG_0001_1.HEIC`). The only files it ever removes are its own uploads in `.upload` that failed.
- What happens on the share after a copy has no effect on the app. The share is never checked again.

```
ios/       Xcode project (SwiftUI, PhotoKit) + CopyCore Swift package
android/   Gradle project (Kotlin, Jetpack Compose, WorkManager)
```

## Features (both apps)

| Requirement | How it works |
|---|---|
| Configure server, share, and folder | **Destination** tab: host, optional port and sign-in, then one **Location** row that opens the browser. The share and folder can also be typed under "Enter manually". The folder is created if it doesn't exist. |
| Standard authentication | **SMB:** username + password + optional domain (NTLMv2), always with SMB 3 encryption, or guest (Android also offers anonymous), which can't be encrypted. |
| SMB | SMB 2/3: AMSMB2/libsmb2 on iOS, smbj on Android. NFS isn't supported (see Server notes). |
| Browsing | One browser, like a folder picker on a computer: it starts at the server's **shares** (srvsvc NetShareEnum), then goes into **folders** inside them (files shown dimmed, hidden dot-entries left out). It opens at the folder already selected, with the parent folders on the back stack. **New Folder** creates a folder and opens it. **Choose** sets the share and the folder together. "Open a share by name" handles servers that don't list their shares. |
| What is copied | Every photo and video. **iOS:** for each item, every file Photos stores: the original, the RAW of a RAW+JPEG pair, the Live Photo video, the edited photo, the edited Live Photo video, the edited video, and the edit recipe (`.AAE`). Files are named the way iOS names them when importing to a computer: `IMG_0001.HEIC`, `IMG_0001.MOV`, `IMG_E0001.HEIC`, `IMG_E0001.MOV`, `IMG_0001.AAE`. If an item is edited but Photos has no stored render (e.g. slo-mo), the app asks Photos to render the current version. All bursts and the Hidden album are included. **Android:** every MediaStore image and video, as stored (Motion Photos keep their embedded video). |
| Never duplicate identical files | Before writing, the app looks at the file's name and its numbered variants (`IMG_0001.HEIC`, `IMG_0001_1.HEIC`, …, compared case-insensitively). Sizes are compared first, and a file with a different size is different and is never read. Only a same-size file is read back, and it counts as identical only if both the SHA-256 and the size match. If an identical file exists (under any of those names), it is recorded as the copy and nothing is written. Otherwise the file goes under the first free name. On iOS, an item's files are matched as a group, at one shared number. |
| Verify each copy | 1) Hash the original (SHA-256). 2) Upload it into the `.upload` folder inside the destination path (e.g. `Photos/.upload/`). 3) Read it back there and compare SHA-256 (default; a faster size-only check is available). 4) Once every file of the item is verified, move each one into the destination folder under its final name. The move never replaces an existing file. 5) Check the size at the final location. A folder watcher on the destination folder only ever sees complete, verified files arriving by move. Files inside `.upload` get a short unique prefix, so leftovers from an interrupted run never block a later one. An item is recorded as copied only if every step succeeds for every one of its files. On iOS, all files of an item share one conflict number (`IMG_0001_1.HEIC` goes with `IMG_0001_1.MOV`). If an upload, verification or move fails, the app removes its own files from `.upload`, so no partial or corrupt file is left behind. |
| Track copied photos | A local ledger keyed by photo ID (PHAsset `localIdentifier` / MediaStore `_ID`). It stores the destination, remote path, size, and SHA-256. Entries for photos no longer on the device are pruned automatically. On Android it also stores the item's size: MediaStore reuses IDs when its database is rebuilt, so an entry whose photo now has a different size is dropped too. The ledger is never backed up or moved to a new device. On iOS, adding an entry appends one line to a log next to the snapshot, so a large first copy doesn't rewrite the whole ledger after every photo. |
| Photo info | Tap a photo to open it full screen (swipe between photos, tap to hide the bars, swipe down to close). **ⓘ** or a swipe up shows its info, laid out like the system Photos app: **iOS** a sheet like Photos' Info panel (also from a long press on a thumbnail → *Show Info*), **Android** a *Details* sheet like Google Photos'. It shows the date and time (in the time zone it was taken in, from EXIF), file name, format, megapixels, pixel size and file size, camera, lens, aperture, shutter speed, focal length (and 35 mm equivalent), ISO and exposure compensation, and for videos the resolution, frame rate, codec and length. It also shows the location, with the place name and coordinates; it opens in the Maps app (on iOS with a map preview). Then the kind (Live Photo, Portrait, Screenshot, Burst…, iOS) or the folder on the device (Android). Last, whether CopyPhotos has copied it: when, and the full path on the share, or else which files a copy will write. On iOS, camera details are read from the original file; if it's only in iCloud, a button downloads it (nothing is downloaded otherwise). On Android, the location needs `ACCESS_MEDIA_LOCATION`. |
| Never re-copy automatically | Every automatic path ("Copy N new", auto-copy on launch or library change, background tasks) copies only photos that aren't in the ledger. |
| Copy again anyway | Select photos and copy them (the app confirms if any were already copied), use **Copy all again…**, or use **Forget copy history…**. |
| Delete copied photos from the device | **Delete copied photos from device…** deletes every item on the device that was copied. Having been copied (and verified at the time) is the only criterion; the destination is not consulted. The OS asks you to confirm: iOS moves them to *Recently Deleted*; Android 11+ moves them to the trash. |

Everything goes straight into the destination folder; the app adds no subfolders (only its own `.upload` staging folder). Other options: verification mode and automatic copying (Wi-Fi only and charging-only constraints).

## Architecture

Both apps use the same Model / Controller / View split, with one controller per screen: Photos, Destination and Photo details. Each app has a pure core model with no platform imports (`ios/CopyCore`, `android/…/core`). The concepts and method names are the same on both platforms, as if the code were shared. Platform adapters handle storage, the photo library, SMB and background work. Views talk only to their screen's controller. [CLAUDE.md](CLAUDE.md) has the full layer map and the rules that every change must follow.

## iOS

- Requirements: Xcode 16+ (the project uses synchronized folders, objectVersion 77). The deployment target is iOS 17.
- Open `ios/CopyPhotos.xcodeproj`. Xcode resolves AMSMB2 4.0.3 via SwiftPM. Set your signing team, then build and run.
- `ios/CopyCore` holds the platform-independent code: the remote-filesystem interface, the copy/verify engine, the ledger, the copy service, the destination config and the browser and result models. Run its unit tests on macOS:
  ```
  cd ios/CopyCore && swift test
  ```
- Permissions: full Photos access (needed to see every photo and to delete) and Local Network.
- Automatic copying runs when the app opens, when the photo library changes while the app is open, and in the background via `BGProcessingTask`. iOS decides when that task runs, usually while the device is idle and connected. A copy that's already running gets extra time if you leave the app.
- Files are exported with `PHAssetResourceManager`, downloading from iCloud when needed.
- An item that is edited *after* it was copied is not copied again automatically (it is already recorded as copied). Use copy-again to copy the new edit.

## Android

- Requirements: JDK 17 and the Android SDK (compileSdk 35). minSdk is 26 (Android 8.0).
- `cd android && ./gradlew assembleDebug` (or open the folder in Android Studio). Create `local.properties` with `sdk.dir=…` if Android Studio hasn't.
- Unit tests: `./gradlew testDebugUnitTest`.
- Copies run in a WorkManager job, with a foreground notification that has a Stop action, so they continue when the app is closed. Automatic copying uses:
  - a content-URI trigger that fires when new media appears,
  - an hourly periodic job,
  - a check when the app opens.

  All of them queue the same single "copy new photos" job.
- `ACCESS_MEDIA_LOCATION` is requested so copies keep their GPS EXIF data. Without it, Android strips location from the bytes the app reads.
- Android 14 "selected photos only" access works, but only the selected photos are visible. The app shows a warning when this applies.

## Server notes

**Why there is no NFS**
- NFS was removed. NFSv3 can't refuse to replace a file on rename and has no real user authentication (the server trusts whatever UID/GID the client claims), and Linux's default `secure` export option requires a privileged source port, which Android apps can't use.
- NFSv4 doesn't fix these: RENAME still replaces the target, `secure` still applies, and the only real authentication is Kerberos, which isn't practical on a phone.
- SMB covers the same servers (Linux/Samba, Synology, TrueNAS, Windows, macOS) with username and password, encryption, and no-replace moves.

**SMB**
- SMB 2.0.2 through 3.1.1 are supported; SMB1 isn't.
- Share listing needs a server that allows NetShareEnum. Windows, Samba, and macOS do by default. If it's blocked, type the share name instead.
- Kerberos SMB authentication isn't supported. NTLMv2 works with domain accounts as well as local accounts.

## Not yet tested

- Both projects build: iOS for the simulator and for a device, Android `assembleDebug` with lint clean of errors.
- The unit tests pass. They cover path handling, name conflicts and the identical check (different size never read, same size with different contents, identical at a later number, case-insensitive names), the ledger, verification (including a simulated corrupted upload), multi-file items (Live Photo + edits, shared conflict numbering, a partly failed item is not recorded), and file naming, and the photo-info formatting (shutter speed, aperture, megapixels, video resolution, ISO 6709 video locations, EXIF time zones).
- They haven't been run against real SMB servers or on physical devices. Test against your own server first. "Test connection" on the Destination tab writes, reads back, and deletes a small probe file.

## CI and releases

GitHub Actions workflows live in `.github/workflows/`:

- **Android** (`android.yml`): unit tests, lint, and a debug APK (uploaded as a build artifact) on pushes to `main` and pull requests that touch `android/`.
- **iOS** (`ios.yml`): `swift test` for CopyCore and an unsigned simulator build of the app, on changes under `ios/`.
- **Release** (`release.yml`): runs on every pushed tag. It runs both test workflows, builds the APK, and creates a GitHub release with the APK and its SHA-256 attached. For plain version tags it also publishes to Google Play and the App Store (see below). The version name is the tag without a leading `v`. The version code and iOS build number are minutes since the Unix epoch, so they increase across workflows and reruns. The annotated tag message becomes the release notes for all three. Tags containing `-` (e.g. `v1.2.0-beta1`) only make a GitHub pre-release.

  ```
  git tag -a v1.0.0 -m "First release." && git push origin v1.0.0
  ```
- **Play refresh** (`play-refresh.yml`): runs weekly. If the newest release tag is older than 30 days (repository variable `PLAY_MAX_AGE_DAYS` changes this), it tags the next patch version on the **same commit** as the highest release, with the message "Maintenance update.". It then runs the Release workflow with Google Play only. It can also be run by hand with "force".

#### The `release` environment

All signing keys and store credentials are **environment secrets** in an environment named `release` (Settings → Environments → New environment). Don't store them as repository secrets. Only the three jobs that need them (Android build, Google Play, App Store) run in that environment. Set it up like this:

- **Deployment branches and tags:** choose "Selected branches and tags" and add a **tag** rule `v*`. Branches, pull requests, and other tags then can't reach the secrets, even if someone edits a workflow.
- **Tag ruleset:** in Settings → Rules, restrict who can create, update, or delete `v*` tags. Anyone who can push a `v*` tag can release to both stores.
- **Required reviewers** (optional): every release then waits for your approval. This includes the automatic Play refresh, and a pending approval expires after 30 days. With reviewers set, the refresh only emails you; it doesn't publish by itself.

To sign release APKs with your own key, add these secrets to the `release` environment. Without them, the release gets a debug-signed APK instead, and Google Play is skipped. A debug-signed APK installs, but CI makes a new debug key on every run, so one release can't be installed over another.

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -i release.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

### Google Play

Plain version tags are uploaded as a signed AAB to the **production** track and rolled out to 100%. The lane is in `android/fastlane/Fastfile`. Store listing text and images aren't touched; only "What's new" is set, from the tag message (truncated to 500 characters). Its language is `en-US`, or the repository variable `PLAY_LANGUAGE`.

One-time setup:

1. Create the app in the Play Console and complete the listing, content rating, and data safety forms.
2. App signing: choose **use my own key** and upload the key from `ANDROID_KEYSTORE_BASE64`, using the console's PEPK instructions. You keep the key, Google holds a copy, and APKs from GitHub and from Play have the same signature, so users can switch between them. If Google generates the key instead, the GitHub APK and the Play install can't replace each other.
3. Upload the first AAB by hand (the API can't create the first release). Build one with `./gradlew bundleRelease` and the signing environment variables from the table above. New personal developer accounts must also finish the closed-testing requirement before production.
4. In Google Cloud, create a service account and a JSON key for it. In Play Console → Users and permissions, invite its email with release permissions for this app. Store the JSON as the `release` environment secret `PLAY_SERVICE_ACCOUNT_JSON`.

If managed publishing is turned on in the console, uploaded releases wait until you publish them there.

### App Store

For a tag that's a plain version (`v1.2.0`, not `v1.2.0-beta1`), the release workflow also builds the iOS app with Xcode 27 and uploads it. It then submits the build for App Store review, and the build is released automatically when Apple approves it. The lane is in `ios/fastlane/Fastfile`. "What's New" comes from the annotated tag message:

```
git tag -a v1.2.0 -m "Faster uploads." && git push origin v1.2.0
```

A lightweight tag gets "Bug fixes and improvements." If these `release` environment secrets aren't set, the job is skipped:

| Secret | Value |
|---|---|
| `APPLE_TEAM_ID` | your 10-character team ID |
| `ASC_KEY_ID`, `ASC_ISSUER_ID` | an App Store Connect API key (Users and Access → Integrations), role App Manager or Admin |
| `ASC_KEY_P8_BASE64` | `base64 -i AuthKey_XXXX.p8` |
| `APPLE_DIST_CERT_P12_BASE64` | `base64 -i dist.p12`: an **Apple Distribution** certificate and its private key, exported from Keychain Access |
| `APPLE_DIST_CERT_PASSWORD` | the `.p12` export password |

The App Store provisioning profile is fetched or created on each run. One-time setup in App Store Connect, before the first tag: create the app record (bundle ID `com.copyphotos.app`), and fill in App Privacy, age rating, screenshots, description, and the review contact. After that, each tag only needs a new build and "What's New".

### Encryption and export compliance

Both apps include encryption beyond what the operating system provides: SMB 3 encryption (AES-CCM/GCM), from libsmb2 on iOS and BouncyCastle (through smbj) on Android. These are published, standard algorithms in mass-market software, so under the US EAR the apps are 5D992.c, exported under License Exception ENC §740.17(b)(1). No export license or CCATS is needed. SHA-256 (copy verification) and NTLMv2 (sign-in) aren't data-confidentiality encryption and don't change this. This is the maintainers' reading, not legal advice.

App Store Connect asks for a document only for distribution in France: the French encryption declaration ([Apple: export compliance documentation](https://developer.apple.com/help/app-store-connect/reference/app-information/export-compliance-documentation-for-encryption), checked 2026-11-10). So:

- **Not on the App Store in France:** keep `ITSAppUsesNonExemptEncryption = NO` (Xcode build settings). No documents are required.
- **On the App Store in France:** upload the declaration under App Information → App Encryption Documentation. Once Apple approves it, set `ITSAppUsesNonExemptEncryption = YES` and `ITSEncryptionExportComplianceCode` to the code Apple gives. Without the code, every automated upload stops at "Missing Compliance".

Google Play has no encryption questionnaire, only the acknowledgement in Play Console that the app complies with US export laws.

### Dependency updates

- **Dependabot** (`.github/dependabot.yml`) opens weekly PRs for GitHub Actions (kept pinned to commit SHAs), Gradle dependencies, plugins and the Gradle wrapper, and fastlane (`Gemfile.lock`). A release has to be at least 14 days old before it's proposed. These PRs run the normal CI. Fastlane is only exercised by a release, so test a fastlane update with a pre-release tag (e.g. `v1.2.0-rc1`).
- **Update Xcode packages** (`update-xcode-packages.yml`) does the same for Swift packages added through the Xcode project (AMSMB2), which Dependabot can't handle. It runs weekly, regenerates `Package.resolved`, opens or updates one PR on `deps/xcode-packages`, and starts iOS CI on it. It needs Settings → Actions → General → "Allow GitHub Actions to create and approve pull requests".
- **Not automated:** the Xcode runner (`xcode-27` in `ios.yml` and `release.yml`), the JDK (17), and `compileSdk`/`targetSdk`. Changing those means changing and testing the app itself.
