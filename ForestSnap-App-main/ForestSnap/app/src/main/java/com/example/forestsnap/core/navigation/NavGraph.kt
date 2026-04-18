package com.example.forestsnap.core.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.forestsnap.core.components.EarthLoader
import com.example.forestsnap.features.campsite.CampsiteScanScreen
import com.example.forestsnap.features.dashboard.CameraScreen
import com.example.forestsnap.features.dashboard.DashboardScreen
import com.example.forestsnap.features.dashboard.DashboardViewModel
import com.example.forestsnap.features.map.MapScreen
import com.example.forestsnap.features.settings.SettingsScreen
import com.example.forestsnap.features.syncqueue.SyncQueueScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard     : Screen("dashboard",    "Home",         Icons.Filled.Home)
    object Map           : Screen("map",          "Map",          Icons.Filled.Map)
    object SyncQueue     : Screen("syncqueue",    "Sync",         Icons.Filled.CloudUpload)
    object Settings      : Screen("settings",     "Settings",     Icons.Filled.Settings)
    object Camera        : Screen("camera",       "Camera",       Icons.Filled.CameraAlt)
    object CampsiteScan  : Screen("campsite_scan","Campsite Scan",Icons.Filled.Explore)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val navController    = rememberNavController()
    val drawerState      = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope   = rememberCoroutineScope()
    val sharedLocationViewModel: DashboardViewModel = viewModel()

    val bottomNavItems = listOf(
        Screen.Dashboard,
        Screen.Map,
        Screen.SyncQueue,
        Screen.Settings
    )

    // Track current route for drawer highlight
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    ModalNavigationDrawer(
        drawerState   = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text     = "🌲 ForestSnap",
                    style    = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color    = Color(0xFF1B5E20),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Text(
                    text     = "Forest Fire Detection System",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = Color.Gray,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Standard nav items in drawer
                bottomNavItems.forEach { screen ->
                    NavigationDrawerItem(
                        icon     = { Icon(screen.icon, contentDescription = screen.title) },
                        label    = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        onClick  = {
                            coroutineScope.launch { drawerState.close() }
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Campsite Scan — featured item in drawer
                NavigationDrawerItem(
                    icon     = { Icon(Icons.Filled.Explore, contentDescription = "Campsite Scan", tint = Color(0xFF1B5E20)) },
                    label    = {
                        Column {
                            Text("Campsite Scan", fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                            Text("360° fire risk assessment", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    },
                    selected = currentRoute == Screen.CampsiteScan.route,
                    onClick  = {
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate(Screen.CampsiteScan.route)
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title  = {
                        val title = when (currentRoute) {
                            Screen.Dashboard.route    -> "ForestSnap"
                            Screen.Map.route          -> "Risk Map"
                            Screen.SyncQueue.route    -> "Sync Queue"
                            Screen.Settings.route     -> "Settings"
                            Screen.CampsiteScan.route -> "Campsite Scan"
                            else                      -> "ForestSnap"
                        }
                        Text(title, fontWeight = FontWeight.Bold)
                    },
                    navigationIcon = {
                        // Hide hamburger on camera screen
                        if (currentRoute != Screen.Camera.route) {
                            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Open sidebar")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor       = Color(0xFF1B5E20),
                        titleContentColor    = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
            },
            bottomBar = {
                // Hide bottom nav on camera and campsite scan screens
                if (currentRoute !in listOf(Screen.Camera.route, Screen.CampsiteScan.route)) {
                    NavigationBar {
                        val destination = navBackStackEntry?.destination
                        bottomNavItems.forEach { screen ->
                            NavigationBarItem(
                                icon     = { Icon(screen.icon, contentDescription = screen.title) },
                                label    = { Text(screen.title) },
                                selected = destination?.hierarchy?.any { it.route == screen.route } == true,
                                onClick  = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState    = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController    = navController,
                startDestination = Screen.Dashboard.route,
                modifier         = Modifier.padding(innerPadding)
            ) {
                composable(Screen.Dashboard.route) {
                    DashboardScreen(navController = navController, viewModel = sharedLocationViewModel)
                }
                composable(Screen.Map.route) {
                    TabLoadingWrapper(loadingText = "Loading Map Data…") {
                        MapScreen(viewModel = sharedLocationViewModel)
                    }
                }
                composable(Screen.SyncQueue.route) {
                    TabLoadingWrapper(loadingText = "Checking Sync Queue…") {
                        SyncQueueScreen()
                    }
                }
                composable(Screen.Settings.route) {
                    TabLoadingWrapper(loadingText = "Loading Preferences…") {
                        SettingsScreen()
                    }
                }
                composable(Screen.Camera.route) {
                    CameraScreen(navController)
                }
                composable(Screen.CampsiteScan.route) {
                    CampsiteScanScreen(navController = navController)
                }
            }
        }
    }
}

@Composable
fun TabLoadingWrapper(
    loadingText: String,
    content: @Composable () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        delay(600)
        isLoading = false
    }

    if (isLoading) {
        Box(
            modifier         = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EarthLoader()
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text  = loadingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        }
    } else {
        content()
    }
}