package ai.labs32.khaata.feature.receipts

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.Receipt
import ai.labs32.khaata.core.receipts.ReceiptImagePlan
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.ReceiptAttachResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The receipts attached to a transaction.
 *
 * A horizontal strip of thumbnails with an add tile at the end — the shape every expense app
 * converges on, because it stays one row however many receipts there are and it makes "there is
 * nothing attached yet" and "here is the add button" the same glance.
 *
 * Free users see the same strip with a lock on the add tile rather than no strip at all: a feature
 * that is invisible until you pay for it cannot be wanted, and hiding it means the first a user
 * hears of receipts is a line on the paywall.
 */
/**
 * One thumbnail in the strip.
 *
 * A file and a stable key rather than a [Receipt], so the strip works identically before and
 * after the transaction exists: during entry there is no row to point at yet, and the image is
 * already on disk waiting for one.
 */
data class ReceiptTile(val key: String, val file: File)

@Composable
fun ReceiptStrip(
    tiles: List<ReceiptTile>,
    canAttach: Boolean,
    isAttaching: Boolean,
    onAdd: () -> Unit,
    onOpen: (ReceiptTile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.ReceiptLong,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(KhaataTheme.spacing.small))
            Text(
                text = stringResource(R.string.receipts_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(KhaataTheme.spacing.small))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
            items(tiles, key = { it.key }) { tile ->
                ReceiptThumbnail(
                    file = tile.file,
                    onClick = { onOpen(tile) },
                )
            }

            item(key = "add") {
                AddReceiptTile(
                    locked = !canAttach,
                    isBusy = isAttaching,
                    onClick = onAdd,
                )
            }
        }
    }
}

@Composable
private fun ReceiptThumbnail(file: File, onClick: () -> Unit) {
    val bitmap = rememberReceiptBitmap(file, maxEdge = THUMBNAIL_PX)

    Box(
        Modifier
            .size(TILE_SIZE)
            .clip(KhaataShapeTokens.cardCompact)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image == null) {
            // The row keeps its shape while the image decodes, so the strip does not jump as
            // thumbnails arrive one by one.
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.receipts_open),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AddReceiptTile(locked: Boolean, isBusy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(TILE_SIZE)
            .clip(KhaataShapeTokens.cardCompact)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = !isBusy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isBusy -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )

            else -> Icon(
                imageVector = if (locked) Icons.Default.Lock else Icons.Default.AddAPhoto,
                contentDescription = stringResource(
                    if (locked) R.string.receipts_locked else R.string.receipts_add,
                ),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Why an attach failed, in a sentence for the snackbar.
 *
 * Shared by every screen that attaches, so the same refusal never gets two different wordings.
 */
@Composable
fun receiptErrorText(error: ReceiptAttachResult): String = stringResource(
    when (error) {
        ReceiptAttachResult.Unreadable -> R.string.receipts_error_unreadable
        ReceiptAttachResult.TooManyForTransaction -> R.string.receipts_error_too_many
        ReceiptAttachResult.OutOfSpace -> R.string.receipts_error_out_of_space
        // Never reached: successes are filtered out before they reach the snackbar.
        is ReceiptAttachResult.Attached, is ReceiptAttachResult.Staged -> R.string.receipts_title
    },
)

/** Camera or gallery, asked once, at the moment the user has decided to attach something. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptSourceSheet(
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = KhaataTheme.spacing.xlarge)) {
            SheetAction(
                icon = Icons.Default.AddAPhoto,
                label = stringResource(R.string.receipts_take_photo),
                onClick = onCamera,
            )
            SheetAction(
                icon = Icons.Default.PhotoLibrary,
                label = stringResource(R.string.receipts_choose_photo),
                onClick = onGallery,
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = KhaataTheme.spacing.large,
                vertical = KhaataTheme.spacing.default,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(KhaataTheme.spacing.default))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * A receipt at full size.
 *
 * Full-screen on a dark scrim with pinch-to-zoom and double-tap, which is what anyone who has
 * used a photo viewer expects and is the whole reason to attach a photo of small print. Panning
 * is clamped to the image's own edges so it cannot be dragged off into empty space and lost.
 */
@Composable
fun ReceiptViewerDialog(
    file: File,
    /** Null while the receipt is only staged: there is nothing to hand another app yet. */
    onShare: (() -> Unit)?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
        ) {
            ZoomableReceipt(file = file, modifier = Modifier.fillMaxSize())

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(KhaataTheme.spacing.small),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onShare != null) {
                    IconButton(onClick = onShare) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.receipts_share),
                            tint = Color.White,
                        )
                    }
                }
                IconButton(onClick = { confirmingDelete = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = Color.White,
                    )
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.receipts_delete_title)) },
            text = { Text(stringResource(R.string.receipts_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ZoomableReceipt(file: File, modifier: Modifier = Modifier) {
    val bitmap = rememberReceiptBitmap(file, maxEdge = ReceiptImagePlan.MAX_EDGE_PX)
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) {
            CircularProgressIndicator(color = Color.White)
            return@Box
        }

        Image(
            bitmap = image,
            contentDescription = stringResource(R.string.receipts_title),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        if (scale > 1f) {
                            // Panning is bounded by how far the image actually overflows at this
                            // zoom, so it cannot be flung off screen and left there.
                            val maxX = (size.width * (scale - 1f)) / 2f
                            val maxY = (size.height * (scale - 1f)) / 2f
                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            // Double tap toggles between fit and a useful reading zoom, which is
                            // faster than pinching to read one line of a bill.
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = DOUBLE_TAP_SCALE
                            }
                        },
                    )
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}

/**
 * Decodes [file] off the main thread, sub-sampled for the size it will be shown at.
 *
 * A strip of five receipts decoded at full resolution on the main thread is a visible stall and,
 * on a low-end phone, an OutOfMemoryError. `produceState` keyed on the file means the work is
 * cancelled if the row scrolls away before it finishes.
 */
@Composable
private fun rememberReceiptBitmap(file: File, maxEdge: Int): ImageBitmap? {
    val path = file.absolutePath
    return produceState<ImageBitmap?>(initialValue = null, path, maxEdge) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

                val options = BitmapFactory.Options().apply {
                    inSampleSize =
                        ReceiptImagePlan.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }.getOrNull()
        }
    }.value
}

private val TILE_SIZE = 72.dp

/** Thumbnails are shown at 72dp; decoding beyond ~3x that buys nothing on any density. */
private const val THUMBNAIL_PX = 256

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f
