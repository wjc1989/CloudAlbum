package com.esct.album

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.esct.album.ui.CloudAlbumTheme
import com.esct.album.ui.GalleryScreen
import com.esct.album.ui.GalleryViewModel
import com.esct.album.ui.PreviewScreen
import com.esct.album.ui.UiEvent

class MainActivity : ComponentActivity() {

    private val vm: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CloudAlbumTheme {
                App(vm, onExit = ::finish)
            }
        }
    }
}

@Composable
private fun App(vm: GalleryViewModel, onExit: () -> Unit) {
    val context = LocalContext.current
    var previewIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var dialog by remember { mutableStateOf<UiEvent.Dialog?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Toast -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                is UiEvent.Dialog -> dialog = event
                is UiEvent.Launch -> try {
                    context.startActivity(event.intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Android 9 及以下保存相册需要存储权限
    var pendingSaveIndex by remember { mutableIntStateOf(-1) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingSaveIndex >= 0) {
            vm.saveToAlbum(pendingSaveIndex)
        } else if (!granted) {
            Toast.makeText(context, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
        pendingSaveIndex = -1
    }
    val saveToAlbum: (Int) -> Unit = { index ->
        val needPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            pendingSaveIndex = index
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.saveToAlbum(index)
        }
    }

    Surface(Modifier.fillMaxSize()) {
        GalleryScreen(vm, onImageClick = { previewIndex = it })

        val index = previewIndex
        if (index != null && index < vm.list.size) {
            BackHandler { previewIndex = null }
            PreviewScreen(
                images = vm.list,
                initialIndex = index,
                onDismiss = { previewIndex = null },
                onSetWallpaper = vm::setWallpaper,
                onSaveToAlbum = saveToAlbum,
            )
        } else {
            DoubleBackToExit(onExit)
        }
    }

    vm.busyText?.let { LoadingDialog(it) }

    dialog?.let { d ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(d.title) },
            text = { Text(d.content) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/** 2 秒内连按两次返回键退出（对应 onLastPageBackPress） */
@Composable
private fun DoubleBackToExit(onExit: () -> Unit) {
    val context = LocalContext.current
    var firstBackTime by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - firstBackTime < 2000) {
            onExit()
        } else {
            firstBackTime = now
            Toast.makeText(context, R.string.press_again_to_exit, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun LoadingDialog(text: String) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), tonalElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(text)
            }
        }
    }
}
