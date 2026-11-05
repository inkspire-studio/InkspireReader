package com.inkspire.ebookreader.ui.bookcontent.chaptercontent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inkspire.ebookreader.common.UiState
import com.inkspire.ebookreader.domain.model.Book
import com.inkspire.ebookreader.domain.model.Chapter
import com.inkspire.ebookreader.ui.bookcontent.autoscroll.AutoScrollAction
import com.inkspire.ebookreader.ui.bookcontent.common.LocalAutoScrollViewModel
import com.inkspire.ebookreader.ui.bookcontent.common.LocalChapterContentViewModel
import com.inkspire.ebookreader.ui.bookcontent.common.LocalCombineActions
import com.inkspire.ebookreader.ui.bookcontent.common.LocalDataViewModel
import com.inkspire.ebookreader.ui.bookcontent.common.LocalStylingViewModel
import com.inkspire.ebookreader.ui.bookcontent.common.LocalTTSViewModel
import com.inkspire.ebookreader.ui.bookcontent.content.ChapterContent
import com.inkspire.ebookreader.ui.bookcontent.root.BookContentDataAction
import com.inkspire.ebookreader.ui.composable.MyLoadingAnimation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun BookChapterContent(
    bookInfoProvider: () -> Book,
    initialParagraphIndex: () -> Int,
    currentChapter: () -> Int,
    chapterUiState: () -> UiState<Chapter>,
    isCurrentChapter: () -> Boolean,
    onListStateLoaded: (LazyListState) -> Unit,
    onDispose: () -> Unit
) {
    val combineActions = LocalCombineActions.current
    val dataVM = LocalDataViewModel.current
    val stylingVM = LocalStylingViewModel.current
    val autoScrollVM = LocalAutoScrollViewModel.current
    val ttsVM = LocalTTSViewModel.current
    val chapterContentVM = LocalChapterContentViewModel.current

    val stylingState by stylingVM.state.collectAsStateWithLifecycle()
    val autoScrollState by autoScrollVM.state.collectAsStateWithLifecycle()
    val ttsPlaybackState by ttsVM.state.collectAsStateWithLifecycle()
    val bookChapterContentState by chapterContentVM.state.collectAsStateWithLifecycle()

    var originalOffset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var originalZoom by remember { mutableFloatStateOf(1f) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(currentChapter) {
        dataVM.onAction(BookContentDataAction.LoadChapter(currentChapter()))
    }

    when (val uiState =  chapterUiState()) {
        is UiState.None -> {

        }
        is UiState.Empty -> {
            MyLoadingAnimation(
                stylingState = stylingState
            )
        }
        is UiState.Loading -> {
            MyLoadingAnimation(
                stylingState = stylingState
            )
        }
        is UiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Error: ${uiState.throwable.message}",
                    color = stylingState.stylePreferences.textColor,
                    fontFamily = stylingState.fontFamilies[stylingState.stylePreferences.fontFamily],
                    fontSize = stylingState.stylePreferences.fontSize.sp
                )
            }
        }
        is UiState.Success -> {
            val chapterData = uiState.data()
            val paragraphs = chapterData.content
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialParagraphIndex()
            )
            val isModeActive = ttsPlaybackState.isActivated || autoScrollState.isActivated

            val currentAutoScrollState by rememberUpdatedState(autoScrollState)
            val currentContentState by rememberUpdatedState(bookChapterContentState)
            val currentTTSState by rememberUpdatedState(ttsPlaybackState)

            val isAtBottom by remember {
                derivedStateOf {
                    !listState.canScrollForward && listState.layoutInfo.totalItemsCount > 0
                }
            }
            val text by remember {
                derivedStateOf {
                    if (isCurrentChapter())
                        "${currentContentState.lastVisibleItemIndex} / ${paragraphs.size}"
                    else
                        ""
                }
            }
            val enableUndoButton by remember { derivedStateOf { currentContentState.enableUndoButton } }
            val currentChapterIndex by remember {
                derivedStateOf {
                    if (isCurrentChapter())
                        currentContentState.currentChapterIndex
                    else
                        -1
                }
            }
            val screenHeight by remember { derivedStateOf { currentContentState.screenHeight } }
            val totalChapter by remember { derivedStateOf { bookInfoProvider().totalChapter } }
            val chapterIndex by remember { derivedStateOf { "${currentChapter() + 1} / $totalChapter" } }

            DisposableEffect(listState) {
                onListStateLoaded(listState)
                onDispose { onDispose() }
            }

            LaunchedEffect(isModeActive) {
                if (isModeActive) {
                    originalZoom = 1f
                    originalOffset = Offset.Zero
                }
            }

            LaunchedEffect(listState, isCurrentChapter()) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index }
                    .collect { index ->
                        if (isCurrentChapter() && index != null) {
                            dataVM.onAction(BookContentDataAction.UpdateRecentParagraphToDB(index))
                            chapterContentVM.onAction(BookChapterContentAction.UpdateFirstVisibleItemIndex(index))
                        }
                    }
            }
            LaunchedEffect(listState, isCurrentChapter()) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                    .collect { index ->
                        if (isCurrentChapter() && index != null) {
                            chapterContentVM.onAction(BookChapterContentAction.UpdateLastVisibleItemIndex(index))
                        }
                    }
            }
            LaunchedEffect(originalZoom) {
                if (originalZoom > 1f) {
                    chapterContentVM.onAction(BookChapterContentAction.UpdateEnablePagerScroll(false))
                    chapterContentVM.onAction(BookChapterContentAction.UpdateEnableUndoButton(true))
                } else {
                    chapterContentVM.onAction(BookChapterContentAction.UpdateEnablePagerScroll(true))
                    chapterContentVM.onAction(BookChapterContentAction.UpdateEnableUndoButton(false))
                }
            }

            LaunchedEffect(enableUndoButton) {
                if (!enableUndoButton) {
                    originalZoom = 1f
                    originalOffset = Offset.Zero
                }
            }

            LaunchedEffect(
                listState.interactionSource,
                currentAutoScrollState.autoScrollPreferences.resumeDelay
            ) {
                listState.interactionSource.interactions.collect { interaction ->
                    if (!currentAutoScrollState.isActivated) return@collect

                    when (interaction) {
                        is PressInteraction.Press, is DragInteraction.Start -> {
                            autoScrollVM.onAction(AutoScrollAction.UpdateIsPaused(true))
                        }
                        is PressInteraction.Release, is DragInteraction.Stop, is DragInteraction.Cancel -> {
                            if (currentAutoScrollState.autoScrollPreferences.resumeMode) {
                                delay(currentAutoScrollState.autoScrollPreferences.resumeDelay.toLong())
                                autoScrollVM.onAction(AutoScrollAction.UpdateIsPaused(false))
                            }
                        }
                    }
                }
            }

            LaunchedEffect(
                currentChapter(),
                isCurrentChapter(),
                currentAutoScrollState.autoScrollPreferences.delayTimeAtStart,
            ) {
                if (isCurrentChapter() && currentAutoScrollState.isActivated) {
                    if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                        autoScrollVM.onAction(AutoScrollAction.UpdateIsPaused(true))
                        delay(currentAutoScrollState.autoScrollPreferences.delayTimeAtStart.toLong())
                        autoScrollVM.onAction(AutoScrollAction.UpdateIsPaused(false))
                    }
                }
            }

            LaunchedEffect(
                currentAutoScrollState.isActivated,
                currentAutoScrollState.isPaused,
                currentAutoScrollState.autoScrollPreferences.speed,
                screenHeight,
                isAtBottom,
                isCurrentChapter(),
                currentChapter()
            ) {
                if (isCurrentChapter() && currentAutoScrollState.isActivated && !currentAutoScrollState.isPaused) {
                    while (isActive) {
                        if (isAtBottom) {
                            autoScrollVM.onAction(AutoScrollAction.UpdateIsAnimationRunning(false))
                            if (currentChapter() + 1 < totalChapter) {
                                delay(currentAutoScrollState.autoScrollPreferences.delayTimeAtEnd.toLong())
                                chapterContentVM.onAction(BookChapterContentAction.RequestAnimatedScrollToChapter(currentChapter() + 1))
                            } else {
                                combineActions.onStopAutoScroll()
                            }
                            break
                        }

                        autoScrollVM.onAction(AutoScrollAction.UpdateIsAnimationRunning(true))

                        listState.animateScrollBy(
                            value = screenHeight.toFloat(),
                            animationSpec = tween(
                                durationMillis = currentAutoScrollState.autoScrollPreferences.speed,
                                easing = LinearEasing
                            )
                        )

                        autoScrollVM.onAction(AutoScrollAction.UpdateIsAnimationRunning(false))
                    }
                }
            }

            LaunchedEffect(
                isCurrentChapter(),
                currentTTSState.isActivated,
                currentTTSState.paragraphIndex
            ) {
                if (!isCurrentChapter() || !currentTTSState.isActivated) return@LaunchedEffect

                val isAtTopEdge = currentTTSState.paragraphIndex == currentContentState.firstVisibleItemIndex
                val isAtBottomEdge = currentTTSState.paragraphIndex == currentContentState.lastVisibleItemIndex
                val isOffScreen = currentTTSState.paragraphIndex !in currentContentState.firstVisibleItemIndex..currentContentState.lastVisibleItemIndex
                if (isAtTopEdge || isAtBottomEdge || isOffScreen) {
                    listState.animateScrollToItem(currentTTSState.paragraphIndex)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (!currentAutoScrollState.isActivated) {
                            Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = {
                                    if (!enableUndoButton) {
                                        combineActions.updateSystemBarVisibility()
                                        chapterContentVM.onAction(BookChapterContentAction.SetActiveSelectionIndex(null))
                                    }
                                },
                            )
                        } else {
                            Modifier.combinedClickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = {
                                    if (!enableUndoButton) {
                                        combineActions.updateSystemBarVisibility()
                                        chapterContentVM.onAction(BookChapterContentAction.SetActiveSelectionIndex(null))
                                    }
                                },
                                onDoubleClick = {
                                    autoScrollVM.onAction(AutoScrollAction.UpdateIsPaused(false))
                                }
                            )
                        }
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .background(color = stylingState.containerColor),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(
                                PaddingValues(
                                    start = WindowInsets.systemBars
                                        .union(WindowInsets.displayCutout)
                                        .only(WindowInsetsSides.Start)
                                        .asPaddingValues()
                                        .calculateStartPadding(LayoutDirection.Ltr),
                                )
                            )
                            .padding(start = 4.dp, end = 4.dp)
                            .weight(1f),
                        text = chapterData.chapterTitle,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        style = TextStyle(
                            color = stylingState.stylePreferences.textColor,
                            fontFamily = stylingState.fontFamilies[stylingState.stylePreferences.fontFamily],
                        )
                    )
                    Text(
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(
                                PaddingValues(
                                    end = WindowInsets.systemBars
                                        .union(WindowInsets.displayCutout)
                                        .only(WindowInsetsSides.End)
                                        .asPaddingValues()
                                        .calculateEndPadding(LayoutDirection.Ltr),
                                )
                            )
                            .padding(start = 4.dp, end = 4.dp)
                            .wrapContentWidth(),
                        text = chapterIndex,
                        style = TextStyle(
                            color = stylingState.stylePreferences.textColor,
                            textAlign = TextAlign.Right,
                            fontFamily = stylingState.fontFamilies[stylingState.stylePreferences.fontFamily],
                        ),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .graphicsLayer {
                            val zoom = originalZoom
                            val offset = originalOffset
                            translationX = offset.x
                            translationY = offset.y
                            scaleX = zoom
                            scaleY = zoom
                        }
                        .pointerInput(isModeActive) {
                            if (isModeActive) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown()
                                do {
                                    val event = awaitPointerEvent()
                                    var zoom = originalZoom
                                    zoom *= event.calculateZoom()
                                    zoom = zoom.coerceIn(1f, 10f)
                                    originalZoom = zoom
                                    val pan = event.calculatePan()
                                    val currentOffset = if (zoom == 1f) {
                                        Offset.Zero
                                    } else {
                                        val temp = originalOffset + pan.times(zoom)
                                        val maxX = (size.width * (zoom - 1) / 2f)
                                        val maxY = (size.height * (zoom - 1) / 2f)
                                        Offset(
                                            temp.x.coerceIn(-maxX, maxX),
                                            temp.y.coerceIn(-maxY, maxY)
                                        )
                                    }
                                    originalOffset = currentOffset
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .onSizeChanged { size = it }
                        .onGloballyPositioned { coordinates ->
                            chapterContentVM.onAction(
                                BookChapterContentAction.UpdateScreenHeight(
                                    coordinates.size.height
                                )
                            )
                        },
                    contentPadding = PaddingValues(
                        start = WindowInsets.systemBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Start)
                            .asPaddingValues()
                            .calculateStartPadding(LayoutDirection.Ltr),
                        end = WindowInsets.systemBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.End)
                            .asPaddingValues()
                            .calculateEndPadding(LayoutDirection.Ltr),
                    ),
                ) {
                    itemsIndexed(
                        items = paragraphs,
                        key = { index, _ -> index }
                    ) { index, paragraph ->
                        val highlightsForParagraph = chapterData.highlights[index] ?: emptyList()
                        ChapterContent(
                            index = index,
                            paragraph = paragraph,
                            currentChapterIndex = { currentChapterIndex },
                            isTTSHighlightProvider = { isCurrentChapter() && currentTTSState.isActivated && currentTTSState.paragraphIndex == index },
                            highlights = { highlightsForParagraph },
                            onRequestScrollToOffset = { lineBottomY ->
                                coroutineScope.launch {
                                    if (currentTTSState.isActivated && !listState.isScrollInProgress && isCurrentChapter()) {
                                        val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == index }
                                        if (itemInfo != null) {
                                            val absoluteLineY = itemInfo.offset + lineBottomY
                                            val viewportHeight = listState.layoutInfo.viewportSize.height
                                            val triggerThreshold = viewportHeight * 0.95
                                            if (absoluteLineY > triggerThreshold) {
                                                val targetY = viewportHeight * 0.15
                                                val scrollAmount = absoluteLineY - targetY
                                                if (scrollAmount > 0) {
                                                    listState.animateScrollBy(scrollAmount.toFloat(), tween(800))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "·:*¨༺ ♱✮♱ ༻¨*:·",
                                style = TextStyle(
                                    color = stylingState.stylePreferences.textColor,
                                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                )
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .background(color = stylingState.containerColor),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier
                            .padding(
                                PaddingValues(
                                    start = WindowInsets.systemBars
                                        .union(WindowInsets.displayCutout)
                                        .only(WindowInsetsSides.Start)
                                        .asPaddingValues()
                                        .calculateStartPadding(LayoutDirection.Ltr),
                                    bottom = WindowInsets.systemBars
                                        .only(WindowInsetsSides.Bottom)
                                        .asPaddingValues()
                                        .calculateBottomPadding()
                                )
                            )
                            .padding(start = 4.dp, end = 4.dp),
                        text = text,
                        style = TextStyle(
                            color = stylingState.stylePreferences.textColor,
                            textAlign = TextAlign.Right,
                            fontFamily = stylingState.fontFamilies[stylingState.stylePreferences.fontFamily],
                        ),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}