package com.inkspire.ebookreader.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.inkspire.ebookreader.navigation.Navigator
import com.inkspire.ebookreader.navigation.Route
import com.inkspire.ebookreader.navigation.rememberNavigator
import com.inkspire.ebookreader.ui.home.composable.MyNavigationSuiteScaffold
import com.inkspire.ebookreader.ui.home.libary.LibraryRootScreen
import com.inkspire.ebookreader.ui.home.recentbook.RecentBookRootScreen
import com.inkspire.ebookreader.ui.home.setting.SettingRootScreen

@Composable
fun HomeScreen(
    parentNavigator: Navigator
) {
    val homeNavigator = rememberNavigator(Route.Home.RecentBooks)
    BackHandler(enabled = homeNavigator.currentTab != Route.Home.RecentBooks) {
        homeNavigator.handleBack()
    }
    MyNavigationSuiteScaffold(
        homeNavigator = homeNavigator
    ) {
        NavDisplay(
            backStack = homeNavigator.backStack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<Route.Home.RecentBooks> {
                    RecentBookRootScreen(
                        parentNavigatorAction = parentNavigator::navigateTo,
                        homeNavigatorAction = homeNavigator::switchTab,
                    )
                }
                entry<Route.Home.Library> {
                    LibraryRootScreen(
                        parentNavigatorAction = parentNavigator::navigateTo
                    )
                }
                entry<Route.Home.Settings> {
                    SettingRootScreen()
                }
            }
        )
    }
}