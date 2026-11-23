package com.anistream.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** Asks [requester] for focus, ignoring the case where its node is not attached yet. */
fun FocusRequester.tryFocus(): Boolean = try {
    requestFocus(); true
} catch (_: IllegalStateException) {
    false
}

/**
 * The basic remote target: grows and gets a light ring while focused, OK clicks, holding OK long-clicks.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
    shape: Shape = RoundedCornerShape(10.dp),
    focusedScale: Float = 1.07f,
    color: Color = TvColors.Surface,
    focusedColor: Color = color,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "focus-scale")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    onFocusChange(it.isFocused)
                }
            }
            .clip(shape)
            .background(if (focused) focusedColor else color)
            .then(if (focused) Modifier.border(3.dp, TvColors.Focus, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        content(focused)
    }
}

/** A pill button: white when focused (the TV convention), crimson when [selected], dark otherwise. */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        focusRequester = focusRequester,
        onFocusChange = onFocusChange,
        shape = RoundedCornerShape(22.dp),
        focusedScale = 1.05f,
        color = if (selected) TvColors.Accent else TvColors.SurfaceHigh,
        focusedColor = TvColors.Focus,
    ) { focused ->
        val tint = if (focused) TvColors.Background else TvColors.Text
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Text(text, color = tint, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Portrait poster with the title under it; the title scrolls when focused and too long. */
@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 128.dp,
    subtitle: String? = null,
    progress: Float = 0f,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(if (width != null) modifier.width(width) else modifier.fillMaxWidth()) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            focusRequester = focusRequester,
            onFocusChange = { focused = it; if (it) onFocused() },
            modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Color.Black.copy(alpha = 0.6f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .background(TvColors.Accent),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) TvColors.Text else TvColors.TextDim,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = if (focused) Modifier.basicMarquee() else Modifier,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = TvColors.Gold,
                maxLines = 1,
            )
        }
    }
}

/** A dark-to-clear gradient so text stays readable over artwork. */
fun Modifier.scrim(from: Color = TvColors.Background, horizontal: Boolean = true): Modifier = background(
    if (horizontal) Brush.horizontalGradient(0f to from, 0.55f to from.copy(alpha = 0.7f), 1f to Color.Transparent)
    else Brush.verticalGradient(0f to Color.Transparent, 1f to from),
)

/** Requests focus on [requester] once, after the first layout. */
@Composable
fun InitialFocus(requester: FocusRequester, key: Any? = Unit, enabled: Boolean = true) {
    LaunchedEffect(key, enabled) {
        if (enabled) {
            // A frame for the target to attach, then a second try in case a lazy list placed it late.
            kotlinx.coroutines.delay(16)
            if (!requester.tryFocus()) {
                kotlinx.coroutines.delay(120)
                requester.tryFocus()
            }
        }
    }
}

@Composable
fun CenterMessage(text: String, modifier: Modifier = Modifier, color: Color = TvColors.TextDim) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = color)
    }
}
