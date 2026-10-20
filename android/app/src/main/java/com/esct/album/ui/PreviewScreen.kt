package com.esct.album.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.esct.album.R
import com.esct.album.data.ImageInfo
import com.esct.album.data.WallpaperActions

/** 全屏预览，左右滑动切换，长按弹出操作菜单（对应 uni.previewImage + longPressActions） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    images: List<ImageInfo>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onSetWallpaper: (Int, WallpaperActions.Target) -> Unit,
    onSaveToAlbum: (Int) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { images.size }
    var menuIndex by remember { mutableStateOf<Int?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            ZoomableImage(
                url = images[page].url,
                onTap = onDismiss,
                onLongPress = { menuIndex = page },
            )
        }
        Text(
            text = "${pagerState.currentPage + 1} / ${images.size}",
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp),
        )
    }

    menuIndex?.let { index ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { menuIndex = null }, sheetState = sheetState, dragHandle = null) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                SheetItem(stringResource(R.string.set_home_screen)) {
                    menuIndex = null
                    onSetWallpaper(index, WallpaperActions.Target.HOME)
                }
                HorizontalDivider()
                SheetItem(stringResource(R.string.set_lock_screen)) {
                    menuIndex = null
                    onSetWallpaper(index, WallpaperActions.Target.LOCK)
                }
                HorizontalDivider()
                SheetItem(stringResource(R.string.set_home_and_lock_screen)) {
                    menuIndex = null
                    onSetWallpaper(index, WallpaperActions.Target.BOTH)
                }
                HorizontalDivider()
                SheetItem(stringResource(R.string.save_to_album)) {
                    menuIndex = null
                    onSaveToAlbum(index)
                }
                HorizontalDivider(thickness = 6.dp)
                SheetItem(stringResource(R.string.cancel)) { menuIndex = null }
            }
        }
    }
}

@Composable
private fun SheetItem(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text, fontSize = 16.sp)
    }
}

/** 支持双指缩放、双击放大、放大后拖动；未放大时横向滑动交给 Pager */
@Composable
private fun ZoomableImage(url: String, onTap: () -> Unit, onLongPress: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var loading by remember { mutableStateOf(true) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = clamp((center - tap) * (scale - 1f), scale)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        if (multiTouch || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (newScale <= 1f) Offset.Zero else clamp(offset + event.calculatePan(), newScale)
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onState = { loading = it is AsyncImagePainter.State.Loading },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
        if (loading) CircularProgressIndicator(color = Color.White)
    }
}
