# CLAUDE.md

Working guidance for **Inkspire Reader**, an Android ebook reader: import EPUB, PDF and CBZ files the user picks, then
read them in a Compose reader with TTS, background music, autoscroll, notes, highlights and bookmarks.

[docs/README.md](docs/README.md) is the map of every document. A plan's folder is its state. What is owed but not
planned is in [docs/record/KNOWN_ISSUES.md](docs/record/KNOWN_ISSUES.md).

## Commands

```bash
./gradlew :app:assembleDebug                                             # build, about 2 minutes cold
./gradlew :app:installDebug                                              # install on the connected device
./gradlew :app:testDebugUnitTest --tests "*ExampleUnitTest.addition*"    # one test
adb shell am start -n com.inkspire.ebookreader.debug/com.inkspire.ebookreader.ui.root.MainActivity
```

Use `.\gradlew.bat` in PowerShell. There is no detekt, ktlint or CI, and `:app:lintDebug` currently fails before it starts
because the androidTest classpath does not resolve (see KNOWN_ISSUES), so only the compiler checks a change.

`:app:testDebugUnitTest` covers EPUB parsing and `TextMapper`, and nothing else yet.

`keystore.properties` must exist or Gradle fails while configuring: `app/build.gradle.kts` casts its values to `String`
even for a debug build. Never print or quote that file. This repo is public.

## How to work here

### Delivering work

- **A change is one idea.** Size follows the idea: a coherent change is not chopped up to hit a line budget, and two
  unrelated ideas do not share a commit.
- **Commit straight to `main`, the Google Play release branch; never create a branch unless asked.** One developer, no CI: a
  branch costs a merge nobody asked for. This overrides any default habit of branching off the main branch.
- **`master` is frozen and is never merged into `main`.** It is the final private build, kept with the online source, Firebase
  and Kotzilla for personal use. Change it only when explicitly asked.
- **A green build is not "done".** Compiling proves nothing about behavior. Anything touching the reader, import, TTS,
  persistence or rendering is verified on the device before it is called finished, and the commit waits for that.
- **State the decisions in the summary**: what was chosen, what was rejected, and why. A diff cannot show that.

### Writing code

- **Match the pattern around you, and do not fix what you were not asked to fix.** The code has known layering debt
  (see Architecture). New code follows the rules here; old code changes when the task reaches it.
- **A file holds one thing**: one screen, one component group, one concern. Length is the symptom, not the rule.
- **A function does one thing.** A composable past ~80 lines is normally hiding sections that want names.
- **Extract on the second consumer, not the first.** A shared type built for one consumer has no context to shape it, and
  once the second arrives a near-copy is how two implementations drift apart. This is about behavior, not dimensions.
- **A dp value is written where it is used, never lifted into a named constant.** A name moves the value away from what it
  measures. A computed dimension, a `Shape` and a `Modifier` can still earn a name.
- **No model in a vacuum.** Build a type when a consumer needs it, and add a dependency when the code needing it is written.
- **A composable never hands out `::localFunction`; pass a lambda that calls it.** Strong skipping memoizes the reference at
  first composition without keying it on what the function captures, so it runs against stale values.
- **`delay` takes a `Duration`**: `delay(FooDwellMs.milliseconds)`, never `delay(FooDwellMs)`.

### Documenting code

- **KDoc every new type and say what it is for**: purpose, not a paraphrase of the signature.
- **Document what the code cannot say**: units, edge cases, the reason for a non-obvious choice, anything that fails silently.
- **Do not document history in KDoc.** What something replaced or used to be called belongs to git.

### Standing rules

- **Books come only from files the user picks.** No online catalog, scraper or download of book content: the app is going to
  Google Play, and sourcing third-party books from websites is a policy risk there.
- **The app makes no network calls and declares no `INTERNET` permission.** Adding either, or an analytics or monitoring SDK,
  changes the Play data safety declaration, so it is a decision to raise rather than a dependency to add.
- **Absent, not disabled.** A control that changes nothing is worse than a missing one, so a verb with no op behind it does
  not appear. The exception is a control gated by a continuous control beside it, because hiding it moves the layout mid-drag.
- **New user-facing text is a string resource**, never a literal in Kotlin. A sentence is one string with placeholders, and a
  count is a `<plurals>`, because word order differs by language. Most existing text is still hard-coded; a screen moves
  when the task reaches it. There are no translations yet. Something already in someone's own words, like a book title or
  a category name, stays as it is.
- **A string speaks to the user, never to the next developer.** No internal names, and no explaining how the code gets there.
- **UI symbols are `res/drawable/ic_*.xml` through `painterResource`**, never Material icons.
- **Both light and dark are first-class.** App chrome uses `BookShelfTheme` (`ui/theme`); the reader's page colors come from
  the user's `StylingState`, so reader chrome must stay legible over colors we did not choose.

## Architecture

Single module `:app`, package `com.inkspire.ebookreader`, source under `app/src/main/java`. Kotlin, Compose, Navigation3,
Room, DataStore, Koin, WorkManager, Media3. Versions live in `gradle/libs.versions.toml`. minSdk 26, targetSdk 36.

**Layers:** `ui` → `domain/usecase` → `domain/repository` (interfaces) → `data/repository` (Impl) → `data/database` DAOs.
A use case here is a **per-feature facade**, such as `LibraryUseCase`, not a single action. No DAO is imported outside `data/`:
keep it that way. Known debt that new code must not copy: `domain/repository` interfaces expose Room entities, and
`NoteViewModel` and `HiddenTextViewModel` skip the use case layer.

`common/` is only `UiState` and `DeviceConfiguration`; helpers and `BookImporter` are in `util/`.

**Screens are `onAction` ViewModels.** Each folder holds `XRootScreen` (gets the ViewModel, collects state), `XScreen`,
`XState`, a sealed `XAction`, `XViewModel`, and `composable/`. The ViewModel has one private `MutableStateFlow`, exposes one
`StateFlow<XState>` and one `fun onAction(action: XAction)`. Reference: `ui/home/libary/`. Morph Launcher forbids this
pattern; here it is the established one (23 of 26 ViewModels), so new screens follow it.

**The reader is different.** `ui/bookcontent/root/BookContentRootScreen.kt` creates about 14 ViewModels and provides them through
`staticCompositionLocalOf` (`ui/bookcontent/common/LocalProvider.kt`). Children read `LocalXViewModel.current`, and
`CombineActions` in the root orchestrates across them. Read the root before adding reader state.

**Rendering is our own Compose, not Readium's navigator.** A `HorizontalPager` per chapter holds a `LazyColumn`
(`chaptercontent/BookChapterContent.kt`), and `content/ChapterContent.kt` routes each paragraph string by regex
(`common/ContentPattern.kt`) to an image, header or paragraph component. Readium is used only to open files on import.

**Import is the workers** (`worker/`): `EPUBImportWorker` (Readium, Jsoup), `CBZImportWorker` (Readium), `PDFImportWorker`
(pdfbox-android), and `EPUBExportWorker` (epublib, export only). They turn every format into the one stored shape below.

**EPUB parsing lives in `worker/epub/`, free of Android and Readium, so it is unit tested; add a failing book's case to its tests.**
`EpubChapterPlanner` decides the chapters: the spine says what the book contains, and the table of contents only names chapters
and marks splits, so a sparse or oversized one cannot lose content. `EpubHtmlParser` turns a file into paragraphs and images, and
`EpubImageResolver` finds an image's file by resolving its path against its chapter. Stylesheet formatting is not read: only tags.

**Persistence.** `data/database/LocalBookDatabase.kt` is Room, version 1, with no migrations and `exportSchema = false`.
`ChapterContentEntity.content` is a `List<String>` stored as JSON: paragraphs with inline HTML tags, and image paths as
paragraphs. Image and music files are in `filesDir`, with their paths in the DB. Preferences are in
`data/repository/DatastoreRepositoryImpl.kt`.

- **A Room schema change needs a written migration and a version bump.** Without one, `fallbackToDestructiveMigration` in
  `di/KoinModule.kt` wipes the user's library on upgrade, silently.
- **The stored chapter strings are a format.** A change to how a worker writes them must also work for books already imported.
  A `<` from a book's text is stored as a marker (`util/StoredText`), so anything that shows or exports stored text must decode
  it, and a new tag must be drawn by `TextMapper`, or it shows on screen as text.
- **Never change the application ID casually.** `ContentPattern.linkPattern` matches stored paths on
  `.inkspire.ebookreader[.debug]/files/`, so a new ID stops image detection, silently.

**TTS and music are one service.** `service/TTSService` is a Media3 `MediaSessionService` with ExoPlayer and a
`ForwardingPlayer`. `ui/bookcontent/tts/TTSManager` (a Koin single) wraps Android `TextToSpeech`, and `TTSViewModel` connects
through a `MediaController`. ExoPlayer plays the chosen music or `asset:///silent.mp3`, which keeps the session alive.

**DI** is `di/KoinModule.kt`, started in `App.kt`: `viewModelOf(::X)`, or `viewModel { X(bookId = it.get()) }` for runtime
parameters; `factoryOf` for use cases; `singleOf(...).bind<>()` for repositories.

**Navigation** is two nested `NavDisplay`s, each with its own back stack: `ui/root/MainActivity` (Home, BookContent, BookDetail,
BookWriter) and `ui/home/HomeScreen` (the tabs). Routes are in `navigation/Route.kt`. Adaptive layout reads
`common/DeviceConfiguration`.

**Older installs may hold books with `fileType = "epub-online"`,** imported before the online source was removed. Their chapters
are in the DB like any other book, so they stay readable. Do not assume every book has a local file behind `storagePath`: on these
it is a URL.

### Names that look wrong and are load-bearing

Do not "fix" these in passing, since they are package paths and identifiers used everywhere: `ui/home/libary`, `headerPatten`,
the `bottombar/default` package, `BookShelfTheme`, and the root project name `Bookshelf3`.

### Declared but unused

Readium navigator, opds and lcp, `richeditor` (`ui/bookwriter/BookWriter.kt` is a stub),
and kotlinx immutable collections have no use in source. Check before assuming one of them is the existing way.

## Conventions

- Formatting is Android Studio's Reformat Code, with hard wrap at 127 to match [.editorconfig](.editorconfig). Leave
  "Cleanup code" unchecked.
- Component names in feature `composable/` folders take the `My` prefix (`MyBookChip`).
- Measure performance on a release build: a debug build composes several times slower, so a fix tuned for it can make release worse.
