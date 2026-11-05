# Known issues

Owed but not planned. Delete an entry in the commit that fixes it.

## Build and tooling

- **No real tests.** The suite is one `ExampleUnitTest` method and one instrumented stub.
- **`:app:lintDebug` cannot run.** `debugAndroidTestCompileClasspath` does not resolve: `androidx.concurrent:concurrent-futures`
  is held at strictly 1.1.0 while `androidx.test.ext:junit:1.3.0` asks for 1.2.0. `:app:assembleDebug` is not affected.
- **A debug build needs `keystore.properties`**, because `app/build.gradle.kts` casts the signing values to `String` unconditionally.
- **No static analysis.** Morph Launcher runs detekt and ktlint over a baseline; this project has neither.
- **Release builds are not minified** (`isMinifyEnabled = false`).
- **Build output is tracked** under `app/release/`.

## Data

- **Room has no exported schema** (`exportSchema = false`), so a future migration has nothing to be tested against.

## Layering

- `domain/repository` interfaces expose Room entities, and `domain/usecase/BookContentUseCase`, `util/HighlightUtil` and
  `ui/home/libary/LibraryScreen` import `data/mapper`.
- `service/TTSService` injects `ui/bookcontent/tts/TTSManager`, so a service depends on the UI package.
- `NoteViewModel` and `HiddenTextViewModel` use repositories directly, and `MusicSettingViewModel` does file I/O.

## UI

- **Most user-facing text is hard-coded**: about 124 literal `Text` lines against 4 `stringResource` calls.

## Dependencies

- Declared with no use in source: Readium navigator, opds and lcp, `richeditor`, kotlinx immutable collections.
