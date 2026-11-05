package com.inkspire.ebookreader.navigation

import androidx.navigation3.runtime.NavKey
import com.inkspire.ebookreader.R

data class HomeNavItem(
    val label: Int,
    val icon: Int,
    val route: NavKey
)

val homeNavItems = listOf(
    HomeNavItem(
        label = R.string.recents,
        icon = R.drawable.ic_home,
        route = Route.Home.RecentBooks
    ),
    HomeNavItem(
        label = R.string.library,
        icon = R.drawable.ic_book_list,
        route = Route.Home.Library
    ),
    HomeNavItem(
        label = R.string.settings,
        icon = R.drawable.ic_setting,
        route = Route.Home.Settings
    ),
)