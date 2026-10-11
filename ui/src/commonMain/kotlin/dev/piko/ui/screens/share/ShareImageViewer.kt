package dev.piko.ui.screens.share

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.piko.ui.platform.LocalPikoPlatform
import io.github.nihildigit.pikpak.FileStat

/**
 * Share images have signed thumbnail URLs, not a normal own-drive file ID.
 * Use those URLs directly without consuming the account's temporary cloud quota.
 * A full-resolution share endpoint can be wired in without changing the viewer.
 */
@Composable
fun ShareImageViewer(
    images: List<FileStat>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onOpenOriginal: (FileStat) -> Unit,
) {
    if (images.isEmpty()) return
    var controlsVisible by remember { mutableStateOf(true) }
    LocalPikoPlatform.current.FullscreenDialog(
        onDismiss = onDismiss,
        immersive = true,
        systemBarsVisible = controlsVisible,
    ) {
        val pager = rememberPagerState(initialPage = initialIndex.coerceIn(0, images.lastIndex)) { images.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val file = images[page]
                var zoom by remember(file.id) { mutableFloatStateOf(1f) }
                val transform = rememberTransformableState { factor, _, _ ->
                    zoom = (zoom * factor).coerceIn(1f, 4f)
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (file.thumbnailLink.isNotBlank()) {
                        AsyncImage(
                            model = file.thumbnailLink,
                            contentDescription = file.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                                .graphicsLayer { scaleX = zoom; scaleY = zoom }
                                .transformable(state = transform)
                                .pointerInput(file.id) {
                                    detectTapGestures(
                                        onTap = { controlsVisible = !controlsVisible },
                                        onDoubleTap = { zoom = if (zoom > 1f) 1f else 2.5f },
                                    )
                                },
                        )
                    } else {
                        TextButton(onClick = { onOpenOriginal(file) }) {
                            Text("预览图不可用，点击加载原图", color = Color.White)
                        }
                    }
                }
            }
            if (controlsVisible) {
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Color.White)
                    }
                    Text(
                        images[pager.currentPage].name + "  (" + (pager.currentPage + 1) + "/" + images.size + ")",
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    TextButton(onClick = { onOpenOriginal(images[pager.currentPage]) }) {
                        Text("原图", color = Color.White)
                    }
                }
            }
        }
    }
}
