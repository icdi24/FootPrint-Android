package me.footprint.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import me.footprint.app.ui.AppViewModel
import me.footprint.app.ui.HomeScreen
import me.footprint.app.ui.LoginScreen
import me.footprint.app.ui.MainScaffold
import me.footprint.app.ui.theme.FootprintTheme
import me.footprint.app.ui.theme.PixelColors
import me.footprint.app.usage.UsageAccess
import me.footprint.app.usage.UsageReporter

/**
 * 唯一的 Activity。界面全部由 Compose 绘制。
 *
 * 前后台上报（文档 16.1 决策 4）：
 *   onStart（进前台）→ 上报一次今日累计
 *   onStop （切后台）→ 再上报一次
 * 上报内部带 60 秒节流，来回切 App 不会刷接口。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FootprintTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PixelColors.Bg,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.systemBars),
                    ) {
                        RootScreen()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        reportInBackground()
    }

    override fun onStop() {
        super.onStop()
        reportInBackground()
    }

    /** 上报不阻塞界面：用 lifecycleScope 起协程，结果只写本地标记 */
    private fun reportInBackground() {
        val ctx = applicationContext
        lifecycleScope.launch {
            UsageReporter.reportNow(ctx)
        }
    }
}

/**
 * 根界面：按登录态切换登录页 / 主页。
 *
 * 用 LifecycleEventObserver 监听 ON_START：
 * 从系统「使用情况访问」设置页返回时 Activity 不会重建，必须自己重新检测权限并刷新数据。
 */
@Composable
private fun RootScreen(vm: AppViewModel = viewModel()) {
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    DisposableEffect(lifecycleOwner, loggedIn) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                vm.refreshGranted()
                vm.refreshAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (loggedIn) {
        MainScaffold(
            vm = vm,
            onOpenUsageSettings = { UsageAccess.openSettings(context) },
        )
    } else {
        LoginScreen(vm = vm)
    }
}
