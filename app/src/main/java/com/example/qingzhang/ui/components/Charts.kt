package com.example.qingzhang.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.qingzhang.domain.CategoryShare
import com.example.qingzhang.domain.TrendPoint
import com.example.qingzhang.domain.formatCents
import java.util.Locale

@Composable
fun TrendChart(points: List<TrendPoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty() || points.all { it.incomeCents == 0L && it.expenseCents == 0L }) {
        EmptyState("暂无趋势数据", "当前周期内还没有收支记录", modifier)
        return
    }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline
    val incomeColor = MaterialTheme.colorScheme.tertiary
    val expenseColor = MaterialTheme.colorScheme.error
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("■", color = incomeColor, style = MaterialTheme.typography.labelSmall)
            Text(" 收入    ", style = MaterialTheme.typography.labelSmall)
            Text("■", color = expenseColor, style = MaterialTheme.typography.labelSmall)
            Text(" 支出", style = MaterialTheme.typography.labelSmall)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .semantics { contentDescription = "收支趋势柱状图" },
        ) {
            val bottom = size.height - 30.dp.toPx()
            val top = 12.dp.toPx()
            val chartHeight = bottom - top
            val maxValue = points.maxOf { maxOf(it.incomeCents, it.expenseCents) }.coerceAtLeast(1L)
            val step = size.width / points.size
            val barWidth = (step * 0.24f).coerceIn(2.dp.toPx(), 12.dp.toPx())
            val labelPaint = Paint().apply {
                color = labelColor.toArgb()
                textSize = 10.sp.toPx()
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            drawLine(gridColor, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
            points.forEachIndexed { index, point ->
                val center = step * index + step / 2
                val incomeHeight = chartHeight * point.incomeCents.toFloat() / maxValue.toFloat()
                val expenseHeight = chartHeight * point.expenseCents.toFloat() / maxValue.toFloat()
                if (point.incomeCents > 0) {
                    drawLine(
                        incomeColor,
                        Offset(center - barWidth * 0.6f, bottom),
                        Offset(center - barWidth * 0.6f, bottom - incomeHeight),
                        barWidth,
                        StrokeCap.Round,
                    )
                }
                if (point.expenseCents > 0) {
                    drawLine(
                        expenseColor,
                        Offset(center + barWidth * 0.6f, bottom),
                        Offset(center + barWidth * 0.6f, bottom - expenseHeight),
                        barWidth,
                        StrokeCap.Round,
                    )
                }
                if (points.size <= 12 || index % 5 == 0 || index == points.lastIndex) {
                    drawContext.canvas.nativeCanvas.drawText(
                        point.label,
                        center,
                        size.height - 8.dp.toPx(),
                        labelPaint,
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryDonut(categories: List<CategoryShare>, totalCents: Long, modifier: Modifier = Modifier) {
    if (categories.isEmpty() || totalCents <= 0) {
        EmptyState("暂无分类数据", "记录支出后可查看分类占比", modifier)
        return
    }
    val labelColor = MaterialTheme.colorScheme.onSurface
    Column(modifier) {
        Box(modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp)) {
            Canvas(modifier = Modifier.size(160.dp).semantics { contentDescription = "支出分类环形图" }) {
                val stroke = 24.dp.toPx()
                val diameter = size.minDimension - stroke
                var startAngle = -90f
                drawArc(
                    Color(0xFFE5E9E7),
                    0f,
                    360f,
                    false,
                    Offset(stroke / 2, stroke / 2),
                    Size(diameter, diameter),
                    style = Stroke(stroke),
                )
                categories.forEach { category ->
                    val sweep = (category.percent * 3.6).toFloat()
                    drawArc(
                        Color(category.color),
                        startAngle,
                        sweep,
                        false,
                        Offset(stroke / 2, stroke / 2),
                        Size(diameter, diameter),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    startAngle += sweep
                }
                val paint = Paint().apply {
                    color = labelColor.toArgb()
                    textSize = 14.sp.toPx()
                    textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawText(
                    formatCents(totalCents),
                    size.width / 2,
                    size.height / 2 + 5.dp.toPx(),
                    paint,
                )
            }
        }
        categories.forEach { category ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = Color(category.color), modifier = Modifier.padding(end = 8.dp))
                Text(category.name, modifier = Modifier.weight(1f))
                Text("${String.format(Locale.CHINA, "%.1f", category.percent)}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatCents(category.amountCents), modifier = Modifier.padding(start = 12.dp))
            }
            LinearProgressIndicator(
                progress = { (category.percent / 100).toFloat() },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Color(category.color),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}
