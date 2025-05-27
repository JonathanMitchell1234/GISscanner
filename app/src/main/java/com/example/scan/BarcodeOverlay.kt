package com.example.scan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.barcode.common.Barcode

class BarcodeOverlay(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    var barcodes: List<Barcode> = emptyList()
    var imageWidth: Int = 0
    var imageHeight: Int = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageWidth <= 0 || imageHeight <= 0) return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val imgW = imageWidth.toFloat()
        val imgH = imageHeight.toFloat()
        val viewRatio = viewW / viewH
        val imgRatio = imgW / imgH
        val scale: Float
        val offsetX: Float
        val offsetY: Float
        if (viewRatio > imgRatio) {
            scale = viewH / imgH
            offsetX = (viewW - imgW * scale) / 2f
            offsetY = 0f
        } else {
            scale = viewW / imgW
            offsetX = 0f
            offsetY = (viewH - imgH * scale) / 2f
        }
        for (barcode in barcodes) {
            barcode.boundingBox?.let { rect ->
                val left = rect.left * scale + offsetX
                val top = rect.top * scale + offsetY
                val right = rect.right * scale + offsetX
                val bottom = rect.bottom * scale + offsetY
                canvas.drawRect(left, top, right, bottom, paint)
            }
        }
    }
}
