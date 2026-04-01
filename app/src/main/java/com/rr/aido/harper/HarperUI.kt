package com.rr.aido.harper

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HarperUnderlinePainter(rects: List<RectF>, onClick: (Int) -> Unit) {
    val density = LocalDensity.current.density

    Box(modifier = Modifier
        .fillMaxSize()
        .pointerInput(rects) {
            detectTapGestures { tapOffset ->
                for ((index, rect) in rects.withIndex()) {
                    // Expand hit area for easy tapping
                    val hitPad = 24 * density
                    if (tapOffset.x >= rect.left - hitPad &&
                        tapOffset.x <= rect.right + hitPad &&
                        tapOffset.y >= rect.top - hitPad &&
                        tapOffset.y <= rect.bottom + hitPad
                    ) {
                        onClick(index)
                        return@detectTapGestures
                    }
                }
            }
        }) {

        Canvas(modifier = Modifier.fillMaxSize()) {
            val redColor = Color(0xFFE53935)
            for (rect in rects) {
                val startX = rect.left
                val endX = rect.right
                if (endX <= startX) continue

                // The RectF from AccessibilityNodeInfo gives screen-absolute
                // pixel coordinates of the character glyph.
                // We draw the squiggle at rect.bottom + 1dp so it sits
                // immediately beneath the text, not inside the glyph area.
                val baseY = rect.bottom + 1f * density

                val path = Path()
                path.moveTo(startX, baseY)
                var currentX = startX
                val waveLength = 7f * density
                val amplitude = 2.5f * density
                var up = true

                while (currentX < endX) {
                    val nextX = (currentX + waveLength).coerceAtMost(endX)
                    val controlX = currentX + waveLength / 2f
                    val controlY = if (up) baseY - amplitude else baseY + amplitude
                    path.quadraticTo(controlX, controlY, nextX, baseY)
                    currentX = nextX
                    up = !up
                }

                drawPath(
                    path = path,
                    color = redColor,
                    style = Stroke(width = 2.5f * density)
                )
            }
        }
    }
}

@Composable
fun HarperTypoCard(
    lint: HarperManager.HarperLint,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .width(300.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Text(
                    text = lint.message,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (!lint.suggestion.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Suggestion:",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2E2E2E))
                            .clickable { onApply(lint.suggestion) }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lint.suggestion,
                            color = Color(0xFF90CAF9),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("Ignore", color = Color.Gray)
                    }
                }
            }
        }
    }
}
