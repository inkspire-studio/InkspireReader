package com.inkspire.ebookreader.ui.home.libary

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LibraryRootScreen(
    parentNavigatorAction: (NavKey) -> Unit
) {
    val viewModel = koinViewModel<LibraryViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LibraryScreen(
        state = state,
        onAction = viewModel::onAction,
        parentNavigatorAction = parentNavigatorAction
    )
}