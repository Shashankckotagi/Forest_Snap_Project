package com.example.forestsnap.features.dashboard

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class MLScorer(private val context: Context) {

    private val interpreter: Interpreter by lazy {
        val assetFd = context.assets.openFd("model_unquant.tflite")
        val stream = FileInputStream(assetFd.fileDescriptor)
        val buffer = stream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFd.startOffset,
            assetFd.declaredLength
        )
        Interpreter(buffer)
    }

    // Returns risk score 0.0 (safe) to 1.0 (extreme risk)
    fun scoreImage(bitmap: Bitmap): Float {
        val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * 224 * 224 * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        for (y in 0 until 224) {
            for (x in 0 until 224) {
                val pixel = resized.getPixel(x, y)
                byteBuffer.putFloat((pixel shr 16 and 0xFF) / 255.0f)
                byteBuffer.putFloat((pixel shr 8  and 0xFF) / 255.0f)
                byteBuffer.putFloat((pixel        and 0xFF) / 255.0f)
            }
        }

        // labels.txt order: 0=High_Risk, 1=Medium_Risk, 2=Low_Risk
        val output = Array(1) { FloatArray(3) }
        interpreter.run(byteBuffer, output)

        val high   = output[0][0]
        val medium = output[0][1]
        val low    = output[0][2]

        // Weighted continuous score: LOW=0.1, MEDIUM=0.5, HIGH=0.9
        return (low * 0.1f) + (medium * 0.5f) + (high * 0.9f)
    }

    fun scoreLabel(score: Float): String = when {
        score >= 0.7f -> "HIGH"
        score >= 0.4f -> "MEDIUM"
        else          -> "LOW"
    }
}
