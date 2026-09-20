package com.groq.voicetyper.ui

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.groq.voicetyper.theme.PrecisionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-memory LRU cache and composable for asynchronously rasterized app icons.
 * Keeps bitmap allocation off the Compose UI thread and shares rasterized
 * icons across PrivacyExclusionsScreen, BucketPickerScreen, and AiStylePickerScreen.
 */
object AppIconCache {
    private const val MAX_CACHE_SIZE = 200
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    fun get(packageName: String): ImageBitmap? = synchronized(cache) {
        cache[packageName]
    }

    fun put(packageName: String, bitmap: ImageBitmap) {
        synchronized(cache) {
            cache[packageName] = bitmap
        }
    }

}

@Composable
fun AsyncAppIcon(
    packageName: String,
    fallbackDrawable: Drawable? = null,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf(AppIconCache.get(packageName)) }

    LaunchedEffect(packageName) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val drawable = fallbackDrawable ?: context.packageManager.getApplicationIcon(packageName)
                    drawable.toBitmap(96, 96).asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
            if (loaded != null) {
                AppIconCache.put(packageName, loaded)
                bitmap = loaded
            }
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap,
            contentDescription = null,
            modifier = modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(
            modifier = modifier
                .size(44.dp)
                .background(colors.panel, RoundedCornerShape(10.dp))
        )
    }
}
