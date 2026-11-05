package com.inkspire.ebookreader.ui.bookcontent.tts

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.ui.text.TextRange
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.Builder
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.inkspire.ebookreader.domain.model.Book
import com.inkspire.ebookreader.domain.usecase.MusicUseCase
import com.inkspire.ebookreader.domain.usecase.TTSContentUseCase
import com.inkspire.ebookreader.domain.usecase.TTSDatastoreUseCase
import com.inkspire.ebookreader.service.TTSService
import com.inkspire.ebookreader.ui.bookcontent.common.ContentPattern.htmlTagPattern
import com.inkspire.ebookreader.ui.bookcontent.common.ContentPattern.linkPattern
import com.inkspire.ebookreader.ui.bookcontent.common.ContentPattern.linkPatternDebug
import com.inkspire.ebookreader.util.StoredText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(UnstableApi::class)
class TTSViewModel(
    private val isReaderMode: Boolean,
    private val application: Application,
    private val ttsManager: TTSManager,
    private val datastoreUseCase: TTSDatastoreUseCase,
    private val contentUseCase: TTSContentUseCase,
    private val musicUseCase: MusicUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(TTSPlaybackState())
    val state = _state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = _state.value
    )

    private val _currentHighlightRange = MutableStateFlow(TextRange.Zero)
    val currentHighlightRange = _currentHighlightRange.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TextRange.Zero
    )

    private val _currentReadingWordOffset = MutableStateFlow(0)
    val currentReadingWordOffset = _currentReadingWordOffset.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0
    )

    private val _event = Channel<TTSUiEvent>(Channel.BUFFERED)
    val event = _event.receiveAsFlow()

    private lateinit var bookInfo: Book
    private var currentChapterTitle: String = ""

    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var realMusicItems = mutableListOf<MediaItem>()

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (_state.value.isActivated) {
                performStop()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            val currentState = _state.value

            if (currentState.isActivated) {
                if (isPlaying) {
                    if (currentState.isPaused) {
                        ttsManager.resumeReading(currentState.paragraphIndex)
                        _state.update { it.copy(isPaused = false) }
                    }
                    syncPlayerVolume(duck = true)
                } else {
                    if (!currentState.isPaused) {
                        ttsManager.pauseReading()
                        _state.update { it.copy(isPaused = true) }
                    }
                    syncPlayerVolume(duck = false)
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            combine(
                datastoreUseCase.ttsPreferences,
                datastoreUseCase.musicPreferences
            ) { ttsPrefs, musicPrefs ->
                val prevMusicState = _state.value.musicPreferences

                _state.update { it.copy(ttsPreferences = ttsPrefs, musicPreferences = musicPrefs) }

                ttsManager.updateSpeed(ttsPrefs.speed)
                ttsManager.updatePitch(ttsPrefs.pitch)
                updateVoiceConfig(ttsPrefs.locale, ttsPrefs.voice)

                if (!musicPrefs.onlyRunWithTTS && musicPrefs.enable && mediaController == null && isReaderMode) {
                    connectToMediaService(false)
                }

                if (prevMusicState != musicPrefs || mediaController != null) {
                    syncMediaItems()
                    syncPlayerVolume(duck = _state.value.isActivated && !_state.value.isPaused)
                }
            }.collectLatest {}
        }

        viewModelScope.launch {
            musicUseCase.getSelectedMusicPaths().collectLatest { selectedItems ->
                realMusicItems = selectedItems.map { media3Item ->
                    Builder()
                        .setUri(media3Item.uri)
                        .setMediaMetadata(createMetadata())
                        .build()
                }.toMutableList()

                if (mediaController != null && _state.value.musicPreferences.enable) {
                    syncMediaItems()
                }
            }
        }

        viewModelScope.launch {
            ttsManager.events.collectLatest { event ->
                handleTTSEvent(event)
            }
        }
    }

    fun onAction(action: TTSAction) {
        when (action) {
            is TTSAction.SetBookInfo -> {
                bookInfo = action.bookInfo
                ttsManager.updateBookInfo(bookInfo)
                refreshAllMediaItemsMetadata()
            }
            is TTSAction.StartTTS -> {
                _state.update { it.copy(paragraphIndex = action.paragraphIndex) }
                startTTSLogic()
            }
            TTSAction.OnPlayPauseClick -> {
                val controller = mediaController ?: return
                if (_state.value.isPaused) {
                    ttsManager.resumeReading(_state.value.paragraphIndex)
                    _state.update { it.copy(isPaused = false) }
                    controller.apply {
                        if (_state.value.musicPreferences.enable)
                            volume = 0.15f * _state.value.musicPreferences.volume
                        play()
                    }
                } else {
                    ttsManager.pauseReading()
                    _state.update { it.copy(isPaused = true) }
                    controller.apply {
                        if (_state.value.musicPreferences.enable)
                            volume = _state.value.musicPreferences.volume
                        else
                            pause()
                    }
                }
            }
            TTSAction.OnStopClick -> performStop()
            TTSAction.OnPlayNextChapterClick -> playChapterOffset(1)
            TTSAction.OnPlayPreviousChapterClick -> playChapterOffset(-1)
            TTSAction.OnPlayNextParagraphClick -> playParagraphOffset(1)
            TTSAction.OnPlayPreviousParagraphClick -> playParagraphOffset(-1)
            is TTSAction.UpdateCurrentChapterData -> handleChapterDataUpdate(action)
            is TTSAction.OnNavigateToRandomChapter -> {
                if (_state.value.isActivated) {
                    _state.update { it.copy(chapterIndex = action.chapterIndex, paragraphIndex = 0) }
                    viewModelScope.launch {
                        contentUseCase.saveBookInfoParagraphIndex(bookInfo.id, 0)
                        loadChapterData(action.chapterIndex, true)
                    }
                }
            }
        }
    }

    private fun syncMediaItems() {
        val controller = mediaController ?: return
        val musicPrefs = _state.value.musicPreferences

        val shouldUseRealMusic = musicPrefs.enable && realMusicItems.isNotEmpty()

        val targetMediaItems = if (shouldUseRealMusic) {
            realMusicItems
        } else {
            listOf(
                Builder()
                    .setUri("asset:///silent.mp3".toUri())
                    .setMediaMetadata(createMetadata())
                    .build()
            )
        }

        val currentCount = controller.mediaItemCount

        val needsUpdate = if (currentCount == 0) true else {
            val firstItem = controller.getMediaItemAt(0)
            val isPlayingSilent = firstItem.requestMetadata.mediaUri.toString().contains("silent.mp3")
            isPlayingSilent == shouldUseRealMusic
        }

        if (needsUpdate || (shouldUseRealMusic && currentCount != realMusicItems.size)) {
            if (shouldUseRealMusic)
                realMusicItems.shuffle()
            if (musicPrefs.enable || _state.value.isActivated)
                controller.setMediaItems(targetMediaItems)
            else
                controller.clearMediaItems()

            if (_state.value.isActivated) {
                controller.prepare()
            }
        }
    }

    private fun syncPlayerVolume(duck: Boolean) {
        val controller = mediaController ?: return
        val baseVolume = _state.value.musicPreferences.volume
        val targetVolume = if (duck) 0.15f * baseVolume else baseVolume

        if (controller.volume != targetVolume) {
            controller.volume = targetVolume
        }
    }

    private fun startTTSLogic() {
        if (mediaController == null) {
            connectToMediaService(true)
        } else {
            _state.update { it.copy(isActivated = true, isPaused = false) }
            ttsManager.updateTTSActivated(true)
            syncMediaItems()

            ttsManager.startReading(_state.value.paragraphIndex)
            mediaController?.play()
            syncPlayerVolume(true)
        }
    }

    private fun performStop(isFromService: Boolean = false) {
        ttsManager.stopReading()

        val musicPrefs = _state.value.musicPreferences
        val keepMusicPlaying = musicPrefs.enable && !musicPrefs.onlyRunWithTTS

        _state.update { it.copy(isActivated = false, isPaused = false) }
        ttsManager.updateTTSActivated(false)
        viewModelScope.launch { _event.send(TTSUiEvent.StopReading) }

        if (isFromService) {
            mediaController?.pause()
            mediaController?.clearMediaItems()
        } else if (keepMusicPlaying) {
            syncPlayerVolume(duck = false)
            syncMediaItems()
        } else {
            mediaController?.pause()
            mediaController?.clearMediaItems()
        }
    }

    private fun handleTTSEvent(event: TTSEvent) {
        when (event) {
            is TTSEvent.CheckPlayNextChapter -> {
                if(_state.value.isActivated)
                    playChapterOffset(1)
            }
            is TTSEvent.CheckPlayPreviousChapter -> {
                if(_state.value.isActivated)
                    playChapterOffset(-1)
            }
            is TTSEvent.CheckPlayNextParagraph -> playParagraphOffset(1)
            is TTSEvent.CheckPauseReading -> mediaController?.pause()
            is TTSEvent.CheckResumeReading -> mediaController?.play()
            is TTSEvent.StopReading -> performStop(true)
            is TTSEvent.OnRangeStart -> _currentHighlightRange.value = TextRange(event.startOffset, event.endOffset)
            is TTSEvent.OnReadOffset -> _currentReadingWordOffset.value = event.offset
        }
    }

    private fun playChapterOffset(offset: Int) {
        val nextIndex = _state.value.chapterIndex + offset
        if (nextIndex in 0 until bookInfo.totalChapter) {
            _state.update { it.copy(chapterIndex = nextIndex, paragraphIndex = 0) }
            viewModelScope.launch {
                contentUseCase.saveBookInfoParagraphIndex(bookInfo.id, 0)
                loadChapterData(nextIndex, true)
            }
        } else if (offset > 0) {
            performStop()
        }
    }

    private fun playParagraphOffset(offset: Int) {
        val nextIndex = _state.value.paragraphIndex + offset
        if (nextIndex in 0 until _state.value.chapterText.size) {
            viewModelScope.launch { contentUseCase.saveBookInfoParagraphIndex(bookInfo.id, nextIndex) }
            _state.update {
                it.copy(
                    paragraphIndex = nextIndex,
                    isPaused = false
                )
            }
            ttsManager.startReading(nextIndex)
            syncPlayerVolume(duck = true)
        } else if (offset > 0) {
            ttsManager.checkPlayNextChapter()
        }
    }

    private suspend fun loadChapterData(chapterIndex: Int, autoPlay: Boolean) {
        val chapter = contentUseCase.getChapterContent(bookInfo.id, chapterIndex)
        chapter?.let { ch ->
            currentChapterTitle = ch.chapterTitle
            val contentToRead = ch.content.map { raw ->
                val cleaned = htmlTagPattern.replace(raw, replacement = "")
                if (linkPattern.containsMatchIn(cleaned) || linkPatternDebug.containsMatchIn(cleaned)) " " else StoredText.decode(cleaned).trim()
            }
            _state.update { it.copy(chapterText = contentToRead) }
            ttsManager.updateChapter(contentToRead)
            refreshAllMediaItemsMetadata()

            contentUseCase.saveBookInfoChapterIndex(bookInfo.id, chapterIndex)
            if (autoPlay && _state.value.isActivated) {
                _state.update { it.copy(isPaused = false) }
                ttsManager.startReading(0)
                mediaController?.play()
            }
        }
    }

    private fun connectToMediaService(isStartTTS: Boolean) {
        val intent = Intent(application, TTSService::class.java)
        Util.startForegroundService(application, intent)

        val sessionToken = SessionToken(application, ComponentName(application, TTSService::class.java))
        controllerFuture = MediaController.Builder(application, sessionToken)
            .setListener(controllerListener)
            .buildAsync()

        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                mediaController?.addListener(playerListener)

                if (_state.value.paragraphIndex != -1 && isStartTTS) {
                    _state.update { it.copy(isActivated = true, isPaused = false) }
                    ttsManager.updateTTSActivated(true)
                    syncMediaItems()
                    ttsManager.startReading(_state.value.paragraphIndex)
                    mediaController?.play()
                } else {
                    syncMediaItems()
                    syncPlayerVolume(duck = false)
                    mediaController?.play()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(application))
    }

    private fun createMetadata(): MediaMetadata {
        val builder = MediaMetadata.Builder()
        if (::bookInfo.isInitialized) {
            builder.setArtworkUri(bookInfo.coverImagePath.toUri())
            builder.setTitle(bookInfo.title)
            builder.setArtist(currentChapterTitle.ifEmpty { "Reading..." })
        }
        return builder.build()
    }

    private suspend fun updateVoiceConfig(savedLanguageTag: String, savedVoiceName: String) {
        val (availableLanguages, availableVoices) = ttsManager.getAvailableVoicesAndLanguages()

        // 1. CRITICAL SAFETY: If the engine is initializing or has no voices, stop.
        if (availableVoices.isEmpty()) return

        // 2. Try to find the exact voice saved in DataStore
        // If savedVoiceName is "" (first run), this returns null.
        var selectedVoice = availableVoices.find { it.name == savedVoiceName }

        // 3. If no specific voice is found (First run OR Engine changed), find a suitable fallback
        if (selectedVoice == null) {

            // Step A: Try to match the saved Locale Name exactly
            var targetLocale = availableLanguages.find { it.toLanguageTag() == savedLanguageTag }

            // Step B: If exact name fails (Common on Samsung), match by ISO Language Code (e.g., "en" vs "en_US")
            if (targetLocale == null) {
                // Parse the saved name or fallback to system default
                val systemLocale = Locale.getDefault()

                // Try to find ANY locale in the engine that matches the System Language
                targetLocale = availableLanguages.find {
                    it.language == systemLocale.language
                }
            }

            // Step C: If we found a compatible locale, pick the default voice for that locale
            if (targetLocale != null) {
                selectedVoice = availableVoices.find {
                    it.locale.language == targetLocale.language && !it.isNetworkConnectionRequired
                }
            }
        }

        // 4. Ultimate Fail-safe:
        // If everything above failed (e.g., System is Vietnamese but Engine only has English),
        // fallback to the Engine's default voice, or just the FIRST available voice.
        if (selectedVoice == null) {
            val engineDefault = try {
                ttsManager.getTTS().defaultVoice
            } catch (_: Exception) { null }
            selectedVoice = engineDefault ?: availableVoices.firstOrNull()
        }

        // 5. Apply the update (Only if we found a valid voice)
        selectedVoice?.let { voice ->
            // If the locale isn't exactly what was requested, update it to the voice's actual locale
            // so the text processing matches the voice.
            ttsManager.updateLanguage(voice.locale)
            ttsManager.updateVoice(voice)
            _state.update { it.copy(currentVoiceQuality = voice.quality.toString()) }

            // Optional: If this was an auto-selection (savedVoiceName was empty),
            // you might want to save this new default to DataStore here so it persists.
//            datastoreUseCase.setTTSLocale(voice.locale.toLanguageTag())
//            datastoreUseCase.setTTSVoice(voice.name)
        }
    }

    private fun handleChapterDataUpdate(action: TTSAction.UpdateCurrentChapterData) {
        val isSameChapter = _state.value.chapterIndex == action.realCurrentChapterIndex
        viewModelScope.launch {
            _state.update { it.copy(chapterIndex = action.chapterIndexToLoadData, paragraphIndex = 0) }
            loadChapterData(action.chapterIndexToLoadData, autoPlay = false)

            if (_state.value.isActivated && !isSameChapter && !_state.value.isPaused) {
                ttsManager.stopReading()
                delay(500)
                ttsManager.startReading(0)
                mediaController?.play()
            }
        }
    }

    private fun refreshAllMediaItemsMetadata() {
        realMusicItems = realMusicItems.map { item ->
            item.buildUpon()
                .setMediaMetadata(createMetadata())
                .build()
        }.toMutableList()

        val controller = mediaController ?: return

        for (i in 0 until controller.mediaItemCount) {
            val currentItem = controller.getMediaItemAt(i)

            val updatedItem = currentItem.buildUpon()
                .setMediaMetadata(createMetadata())
                .build()

            controller.replaceMediaItem(i, updatedItem)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.stopReading()
        mediaController?.stop()
        mediaController?.clearMediaItems()
        mediaController?.release()
    }
}