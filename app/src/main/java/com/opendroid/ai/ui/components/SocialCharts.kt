package com.opendroid.ai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.ui.theme.AppTheme

@Composable
fun SimpleLineChart(
    dataPoints: List<Float>,
    modifier: Modifier = Modifier,
    labels: List<String> = emptyList(),
    lineColor: Color = AppTheme.colors.accentCyan,
    gridColor: Color = AppTheme.colors.borderColor
) {
    if (dataPoints.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No data points available", color = AppTheme.colors.textSecondary, fontSize = 12.sp)
        }
        return
    }

    val maxVal = (dataPoints.maxOrNull() ?: 1f).coerceAtLeast(1f)
    val minVal = (dataPoints.minOrNull() ?: 0f).coerceAtLeast(0f)
    val range = (maxVal - minVal).coerceAtLeast(1f)

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val width = size.width
            val height = size.height
            val spacing = if (dataPoints.size > 1) width / (dataPoints.size - 1) else width

            // Draw horizontal grid lines
            for (i in 0..3) {
                val y = height * (i / 3f)
                drawLine(
                    color = gridColor.copy(alpha = 0.4f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f
                )
            }

            val path = Path()
            val points = mutableListOf<Offset>()

            dataPoints.forEachIndexed { index, value ->
                val x = index * spacing
                val normalizedY = 1f - ((value - minVal) / range)
                val y = (normalizedY * (height - 20f)) + 10f
                points.add(Offset(x, y))
                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            // Draw connecting line
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )

            // Draw circle dots at each point
            points.forEach { point ->
                drawCircle(
                    color = lineColor,
                    radius = 4.dp.toPx(),
                    center = point
                )
            }
        }

        if (labels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                labels.forEach { label ->
                    Text(text = label, color = AppTheme.colors.textSecondary, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun SimpleBarChart(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    barColor: Color = AppTheme.colors.textPrimary
) {
    if (values.isEmpty()) return
    val maxVal = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val width = size.width
            val height = size.height
            val barCount = values.size
            val slotWidth = width / barCount
            val barWidth = (slotWidth * 0.55f).coerceAtLeast(8f)

            values.forEachIndexed { index, value ->
                val barHeight = (value / maxVal) * (height - 10f)
                val x = (index * slotWidth) + ((slotWidth - barWidth) / 2f)
                val y = height - barHeight

                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            labels.take(values.size).forEach { label ->
                Text(text = label, color = AppTheme.colors.textSecondary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun DonutChart(
    proportions: List<Float>,
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    val total = proportions.sum().coerceAtLeast(1f)

    Canvas(modifier = modifier) {
        var startAngle = -90f
        val strokeWidth = 14.dp.toPx()
        val diameter = size.minDimension - strokeWidth
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)

        proportions.forEachIndexed { index, prop ->
            val sweepAngle = (prop / total) * 360f
            val color = colors.getOrElse(index) { Color.Gray }
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            startAngle += sweepAngle
        }
    }
}
