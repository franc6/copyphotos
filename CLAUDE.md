# CopyPhotos — architecture rules

Two native apps (iOS: Swift/SwiftUI, Android: Kotlin/Compose) with the same features. Keep this structure in every change.

## Goal

- **MVC with one controller per screen.** The screens are Photos, Destination (including the location browser) and Photo details.
- **Parallel Model and Controller layers.** Shape them on iOS and Android as if they were shared code: the same concepts, the same responsibilities and the same method names, each written in its own language. No code is actually shared. Only the View layer and the platform adapters may differ.

## Layers

| Layer | iOS | Android |
|---|---|---|
| **Core model** (pure, unit-tested, no platform imports) | `ios/CopyCore` package | `app.copyphotos.core` |
| Platform adapters (storage, photo library, SMB, background) | `CopyPhotos/Storage`, `Photos`, `Remote`, `Background`, `App/AppEnvironment.swift` | `data`, `media`, `remote`, `work`, `AppGraph` in `CopyPhotosApp.kt` |
| **Controllers**, one per screen | `CopyPhotos/Controllers`: `PhotosController`, `DestinationController`, `PhotoDetailsController` | `ui/*ViewModel.kt`: `PhotosViewModel`, `DestinationViewModel`, `PhotoDetailsViewModel` |
| **Views** | `CopyPhotos/UI` | `ui/*Screen.kt`, `LocationBrowser.kt`, `PhotoViewer.kt`, `Components.kt` |

Core concepts. Each one exists on both platforms under the same name:

- **Remote files:**
  - `RemoteFileSystem`, `RemotePath`, `RemoteEntry`, `FileDigest`/`Digest`
  - A typed not-found: `RemoteError.notFound` (iOS) / `RemoteNotFoundException` (Android).
- **Copying:**
  - `CopyEngine`, `CopyOptions(destinationKey, basePath, verifyMode)`, `CopySource`
  - `CopyRecord` (one record per item, holding a list of `CopiedFile`s)
- **Contracts the platforms implement:**
  - `Ledger`: `copiedIDs`, `record`, `add`, `remove`, `prune`, `clear` (Android adds `sourceSizes`)
  - `MediaLibrary`: `allIDs`, `copyItems(ids:)` (Android: `allSizes` instead of `allIds`)
  - `DestinationStore`: `loadConfig`, `saveConfig`, `hasPassword`, `setPassword` (only `DestinationService` calls it)
  - `RemoteConnector`: `open`, `listShares` (the SMB adapter; it reads the password from the secure store itself)
  - Android only: MediaStore reuses IDs, so a record also counts only while the item's size still matches (`CopyService.changedItems`). PHAsset identifiers are UUIDs, so iOS doesn't need this.
- **`CopyService`, which holds the copy use cases:**
  - `copiedIDsOnDevice`, `uncopiedItems` (oldest first), `items(ids:)`, `record`
  - `run` (connects through `DestinationService`; takes no config or password), `forgetHistory`, `recordDeleted`
- **`DestinationService`, which owns the destination's settings and saves its password:**
  - The settings form: `config`, `update`, `hasPassword`, `setPassword`
  - What others ask: `isReady`, `displayURL`, `copyOptions`, `connect`
  - `test`, and the location browser: `browseStack`, `listing(for:)`, `createFolder`, `choose`, `displayURL(for:)`, `endBrowsing`
- **Destination:** `DestinationProbe.check` and its `ProbeResult`, `DestinationConfig` (+ `location`), `SMBAuthMethod`/`SmbAuth`
- **Browsing:** `BrowseLocation` (+ `stack(for:)`), `BrowseListing`, `browsableShares`/`browsableEntries`
- **State and results:** `LibraryAccess`, `CopyRunState`, `CopyResult`, `ResultBanner` (`from`, `nothingNew`, `stopped`, `connectFailed`; a typed choice, not text)
- **Camera metadata rules:** `CameraMetadata` (shutter fraction or seconds, megapixel rounding, 4K/8K, EXIF parsing, camera name, place-name parts), as numbers and cases. `ThumbnailFit` (when a tile shows the whole image). `MetadataText` in the view layer formats them for the locale.
- **Failures, as data:** `FailureReason` (`copy`, `destination`, `notFound`, `other`), `CopyError`, `DestinationError`. Adapters map their own errors (SMB, PhotoKit, MediaStore) to these.

## Rules

1. **Domain logic lives in core.** Examples: which photos to copy, pruning, naming, verification, the write probe, listing rules and result classification (which banner, which error). It never lives in a controller or a view. If both platforms need the same rule, it belongs in both cores.
2. **Controllers** own screen state and actions. They call core services and platform adapters. They never format UI layout, and they never reach into another screen's controller.
3. **Views** bind only to their own controller. They keep nothing but ephemeral UI state (sheets, selection, confirmation dialogs, text being typed). They never touch storage, the ledger, SMB, PhotoKit or MediaStore queries, or WorkManager directly. One exception: views may load thumbnails and full-size images straight through the photo adapter's image API (`PhotoLibrary.images` on iOS, Coil on `MediaItem.uri` on Android), since routing them through a controller would only add one-line forwards.
4. **SMB types stay in `Remote`/`remote`.** Everything else sees only `RemoteFileSystem` and its typed errors.
5. **Only the view layer has user-visible text.** Models, adapters and controllers expose typed data (cases, counts, names) and carry no UI strings and no storage format. The views render text from the platform's translation resources (`Localizable.xcstrings` on iOS, `strings.xml`/`<plurals>` on Android) through one renderer per type, in `UI/Strings.swift` and `ui/Strings.kt`; labels for enums live there too. If two views show the same sentence, duplicating it in the resources is fine. JSON, SQLite and WorkManager `Data` formats live in the adapters.
6. **No hidden globals.** Shared objects come from the composition root (`AppEnvironment` / `AppGraph`) and are passed in. Each service owns its own settings; controllers ask the service to do the work and read a setting only when their screen edits it. Only the connector reads the password, from the secure store (`Keychain` / `PasswordStore`) injected by the composition root. `CopyJobs` (platform adapter, same API on both) runs copies and keeps the result banner.
7. **Don't add one-line wrappers or abstractions with a single use.** Don't re-implement a system component either; for example, use `ContentUnavailableView`.
8. **Change both platforms together.** When you change a core concept or a controller on one platform, make the matching change on the other, or explain in the change why it doesn't apply. Add or adjust core unit tests on both (`ios/CopyCore/Tests`, `android/app/src/test`).

## Checks

- iOS core: `cd ios/CopyCore && swift test`
- iOS app: `xcodebuild -project ios/CopyPhotos.xcodeproj -scheme CopyPhotos -destination 'generic/platform=iOS Simulator' build`
- Android: `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug`
- Layering:
  - `grep -rn "^import android" android/app/src/main/java/app/copyphotos/core` must be empty.
  - `ios/CopyCore` must not import UIKit, SwiftUI, Photos or AMSMB2.
  - `isNotFound` must appear only in the SMB file systems.
  - `grep -rn 'Text("' android/app/src/main/java/app/copyphotos/ui` must be empty (Android text comes from `strings.xml`).
  - `grep -rnE 'String\(format:|String\.format\(|"%\.' ios/CopyPhotos/UI android/app/src/main/java/app/copyphotos/ui` must be empty: numbers, sizes, lengths and durations are formatted by the system (`FormatStyle`, `Measurement`, `Duration`, `ByteCountFormatter`; `NumberFormat`, ICU `MeasureFormat`, `Formatter`). Don't unit-test those system formatters; test only our own rules in core.
- Translations: run once in a pseudolanguage (iOS: scheme → App Language → Accented Pseudolanguage; Android debug: system language en-XA). Every visible string should come out accented.

## Code conventions

- Follow the latest OWASP Top 10. A violation may be acceptable, but must be called out and explained.
- When modifying code that touches a class or method violating the MVC rules above, call out the violation.
- Prefer Clean Code rules and patterns.
- Use existing factories; don't bypass them.
- Prefer passing dependencies in from the composition root over direct instantiation, unless it would mean passing several extra variables to an initializer just to achieve it.
- No function with classic cyclomatic complexity > 10. Decompose only along logical, nameable seams; if none exist, redesign the algorithm.
- DRY applies to multi-statement logic and real rules. A repeated single `if` or expression is not duplication; don't wrap it (see rule 7).
- Every type and function gets a doc comment when written: a terse purpose statement, each parameter's purpose, the return value. Never the how.
- Other comments only when a competent reader would be surprised. Never justify a best practice, explain a rejected alternative, restate code, or write for an LLM. Exception: `// Arrange`, `// Act`, `// Assert` test markers.
- Never pass a password (or a value containing one) by value through layers; only the connector reads it, from the secure store, inside the connect call.
- US English everywhere: code, identifiers, comments, string keys, tests, commits.
- Title-type strings (screen titles, section headings) use US title case; buttons, hints, labels, messages use sentence case.
- Run formatters as a separate step after all edits, never mixed with them.

## Security and cryptography
- Cryptographic invariants are CRITICAL and blocking regardless of perceived exploitability; other OWASP findings may be accepted only when called out and explained.
- Secrets, MACs, hashes, tokens, nonces, and password-derived values are compared only with constant-time primitives; never `==`, `===`, `equals()`, or `strcmp`.
- Never hash a variable-length secret or attacker input to normalize it before comparison, unless it is strictly fixed-size or padded in constant time.
- No branch, ternary, loop bound, or index depends on a secret value.
- Authentication and decryption failures return one generic error in uniform time, whatever the failure stage.
- Never downgrade a violation as masked by noise, "only leaks length," made safe by hashing first, or harmless in isolation.

## Test conventions

- Every change is programmatically tested: write a new test or update an existing one in the same change.
- Each test is divided by `// Arrange`, `// Act`, `// Assert` comments.
- Parameterize tests only when cases are structurally and logically the same test. More than 3 parameters is a strong sign they aren't. If Arrange or Assert would need per-case `if` branches, write separate tests.
- Target 100% line and branch coverage of core; shortfall only when covering needs unusual measures — ask first.
- Never unit-test system localization/formatting routines; test our own rules in core and enforce use of system formatters with the layering greps above.
