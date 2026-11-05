package com.inkspire.ebookreader.ui.home.composable

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.inkspire.ebookreader.navigation.Navigator
import com.inkspire.ebookreader.navigation.homeNavItems

@Composable
fun MyNavigationRail(
    navigator: Navigator
) {
    NavigationRail(
        containerColor = NavigationBarDefaults.containerColor,
        windowInsets = WindowInsets.navigationBars
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Start)
    ) {
        val currentTab = navigator.currentTab
        Spacer(Modifier.weight(1f))
        homeNavItems.forEach { item ->
            NavigationRailItem(
                icon = {
                    Icon(
                        imageVector = ImageVector.vectorResource(item.icon),
                        contentDescription = stringResource(item.label)
                    )
                },
                label = { Text(stringResource(item.label)) },
                selected = currentTab == item.route,
                onClick = {
                    navigator.switchTab(item.route)
                }
            )
        }
        Spacer(Modifier.weight(1f))
    }
}