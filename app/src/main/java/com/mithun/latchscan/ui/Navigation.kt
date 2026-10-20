package com.mithun.latchscan.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mithun.latchscan.LatchApp
import com.mithun.latchscan.scanner.CropEditor
import com.mithun.latchscan.scanner.PagesEditor
import com.mithun.latchscan.scanner.ReviewScreen
import com.mithun.latchscan.scanner.ScannerScreen
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val SCAN = "scan"
    const val REVIEW = "review"
    const val CROP_SESSION = "crop/session/{pageId}"
    const val CROP_DOC = "crop/doc/{docId}/{pageId}"
    const val DOC = "doc/{docId}"
    const val DOC_PAGES = "doc/{docId}/pages/{index}"
}

private fun NavHostController.goHomeThen(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME)
        launchSingleTop = true
    }
}

@Composable
fun LatchNavHost() {
    val nav = rememberNavController()
    val app = LatchApp.instance
    val slide = tween<androidx.compose.ui.unit.IntOffset>(320)

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { slideInHorizontally(slide) { it / 4 } + fadeIn(tween(240)) },
        exitTransition = { slideOutHorizontally(slide) { -it / 6 } + fadeOut(tween(200)) },
        popEnterTransition = { slideInHorizontally(slide) { -it / 6 } + fadeIn(tween(240)) },
        popExitTransition = { slideOutHorizontally(slide) { it / 4 } + fadeOut(tween(200)) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onScan = {
                    app.session.start()
                    nav.navigate(Routes.SCAN)
                },
                onImported = { nav.navigate(Routes.REVIEW) },
                onOpenDoc = { nav.navigate("doc/$it") },
                onTool = { nav.navigate(it) },
            )
        }

        composable(
            Routes.SCAN,
            enterTransition = { scaleIn(tween(320), initialScale = 0.92f) + fadeIn(tween(260)) },
            popExitTransition = { scaleOut(tween(260), targetScale = 0.92f) + fadeOut(tween(220)) },
        ) {
            ScannerScreen(
                onClose = { nav.popBackStack() },
                onReview = { nav.navigate(Routes.REVIEW) { launchSingleTop = true } },
                onCrop = { nav.navigate("crop/session/$it") },
            )
        }

        composable(Routes.REVIEW) {
            ReviewScreen(
                onBackToCamera = {
                    if (nav.previousBackStackEntry?.destination?.route == Routes.SCAN) {
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.SCAN) { popUpTo(Routes.HOME) }
                    }
                },
                onCrop = { nav.navigate("crop/session/$it") },
                onSaved = { id ->
                    nav.navigate("doc/$id") { popUpTo(Routes.HOME) }
                },
            )
        }

        composable(
            Routes.CROP_SESSION,
            arguments = listOf(navArgument("pageId") { type = NavType.StringType }),
            enterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(200)) },
        ) { entry ->
            val pageId = entry.arguments?.getString("pageId")
            val session = app.session
            val page = session.pages.firstOrNull { it.id == pageId }
            if (page == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
                return@composable
            }
            CropEditor(
                original = session.file(page.original),
                initial = page.quad,
                onConfirm = { q ->
                    app.appScope.launch { session.update(page.copy(quad = q)) }
                    nav.popBackStack()
                },
                onCancel = { nav.popBackStack() },
            )
        }

        composable(
            Routes.CROP_DOC,
            arguments = listOf(navArgument("docId") { type = NavType.StringType }, navArgument("pageId") { type = NavType.StringType }),
            enterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(200)) },
        ) { entry ->
            val host = app.docHost(entry.arguments?.getString("docId") ?: "")
            val page = host?.pages?.firstOrNull { it.id == entry.arguments?.getString("pageId") }
            if (host == null || page == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
                return@composable
            }
            CropEditor(
                original = host.file(page.original),
                initial = page.quad,
                onConfirm = { q ->
                    app.appScope.launch { host.update(page.copy(quad = q)) }
                    nav.popBackStack()
                },
                onCancel = { nav.popBackStack() },
            )
        }

        composable(Routes.DOC, arguments = listOf(navArgument("docId") { type = NavType.StringType })) { entry ->
            val docId = entry.arguments?.getString("docId") ?: ""
            DocumentScreen(
                docId = docId,
                onBack = {
                    if (!nav.popBackStack(Routes.HOME, inclusive = false)) nav.goHomeThen(Routes.HOME)
                },
                onOpenPage = { nav.navigate("doc/$docId/pages/$it") },
                onAddPages = {
                    app.session.start(targetDoc = docId)
                    nav.navigate(Routes.SCAN)
                },
            )
        }

        composable(
            Routes.DOC_PAGES,
            arguments = listOf(navArgument("docId") { type = NavType.StringType }, navArgument("index") { type = NavType.IntType }),
        ) { entry ->
            val docId = entry.arguments?.getString("docId") ?: ""
            val index = entry.arguments?.getInt("index") ?: 0
            val host = remember(docId) { app.docHost(docId)?.also { it.sync() } }
            val doc = app.repository.get(docId)
            if (host == null || doc == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
                return@composable
            }
            PagesEditor(
                host = host,
                title = doc.name,
                onBack = { nav.popBackStack() },
                onCrop = { nav.navigate("crop/doc/$docId/${it.id}") },
                initialPage = index,
                bottomBar = { Spacer(Modifier.height(12.dp)) },
            )
        }

        composable("tool/merge_pdf") { MergeScreen(anyDocument = false, onBack = { nav.popBackStack() }) }
        composable("tool/merge_docs") { MergeScreen(anyDocument = true, onBack = { nav.popBackStack() }) }
        composable("tool/compress_pdf") { CompressPdfScreen(onBack = { nav.popBackStack() }) }
        composable("tool/compress_image") { CompressImageScreen(onBack = { nav.popBackStack() }) }
    }
}
