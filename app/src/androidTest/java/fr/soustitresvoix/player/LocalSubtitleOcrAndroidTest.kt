package fr.soustitresvoix.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSubtitleOcrAndroidTest {
    @Test fun importedModelReadsSyntheticSubtitleThroughTheNativeEngine() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val directory = Files.createTempDirectory(instrument.targetContext.cacheDir.toPath(), "ocr-fixture-").toFile()
        val bitmap = Bitmap.createBitmap(880, 190, Bitmap.Config.ARGB_8888)
        try {
            val model = OcrModelStore(directory)
            model.importModel("eng.traineddata") { instrument.context.assets.open("ocr-test/eng.traineddata") }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textSize = 85f
            }
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("OPEN DOOR", 60f, 125f, paint)
            }
            val recognized = model.open("eng").use { it.recognize(bitmap) }
            assertEquals("OPEN DOOR", recognized.replace(Regex("\\s+"), " ").trim())
        } finally {
            bitmap.recycle()
            directory.deleteRecursively()
        }
    }
}
