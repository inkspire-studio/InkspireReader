package com.inkspire.ebookreader.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    sealed interface Home : Route {

        @Serializable
        data object RecentBooks : Home

        @Serializable
        data object Library : Home

        @Serializable
        data object Settings : Route
    }

    @Serializable
    data class BookDetail(val bookId: String) : Route

    @Serializable
    data class BookContent(val bookId: String) : Route

    @Serializable
    data class BookWriter(val bookId: String) : Route
}
