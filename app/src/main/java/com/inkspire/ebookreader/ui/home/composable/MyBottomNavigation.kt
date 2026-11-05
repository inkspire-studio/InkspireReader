package com.inkspire.ebookreader.ui.home.composable

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.inkspire.ebookreader.navigation.Navigator
import com.inkspire.ebookreader.navigation.homeNavItems

@Composable
fun MyBottomNavigation(
    navigator: Navigator
) {
    NavigationBar {
        val currentTab = navigator.currentTab
        homeNavItems.forEach { item ->
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = ImageVector.vectorResource(item.icon),
                        contentDescription = stringResource(item.label)
                    )
                },
                label = {
                    Text(text = stringResource(item.label))
                },
                selected = currentTab == item.route,
                onClick = {
                    navigator.switchTab(item.route)
                }
            )
        }
    }
}
