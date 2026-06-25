package io.github.stream29.codex.lite.utils.images.codec

import io.github.stream29.codex.lite.utils.images.EncodedImage
import io.github.stream29.codex.lite.utils.images.ImageMimeType
import io.github.stream29.codex.lite.utils.images.PromptImageTransformRequest
import io.github.stream29.codex.lite.utils.images.PromptImageTransformer
import kotlinx.coroutines.CancellationException
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

public actual val HostPromptImageTransformer: PromptImageTransformer = SkikoPromptImageTransformer

/**
 * Stateless prompt-image transformer backed by Skia through Skiko.
 */
public object SkikoPromptImageTransformer : PromptImageTransformer {
    override suspend fun transform(request: PromptImageTransformRequest): EncodedImage {
        val plan = request.plan
        val targetDimensions = plan.outputDimensions
        val targetMimeType = plan.outputMimeType
        val source = try {
            Image.makeFromEncoded(request.sourceBytes)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            throw ImageCodecException("Unable to decode prompt image with Skiko", throwable)
        }

        try {
            val surface = Surface.makeRaster(
                ImageInfo(
                    width = targetDimensions.width,
                    height = targetDimensions.height,
                    colorType = ColorType.RGBA_8888,
                    alphaType = ColorAlphaType.PREMUL,
                ),
            )
            try {
                surface.canvas.clear(targetMimeType.backgroundColor)
                val paint = Paint()
                try {
                    paint.isAntiAlias = true
                    paint.isDither = true
                    surface.canvas.drawImageRect(
                        image = source,
                        src = Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                        dst = Rect.makeWH(targetDimensions.width.toFloat(), targetDimensions.height.toFloat()),
                        samplingMode = SamplingMode.LINEAR,
                        paint = paint,
                        strict = true,
                    )
                } finally {
                    paint.close()
                }

                val outputImage = surface.makeImageSnapshot()
                try {
                    val outputData = outputImage.encodeToData(targetMimeType.toSkikoFormat(), targetMimeType.skikoQuality)
                        ?: throw UnsupportedImageCodecException(targetMimeType, "encode")
                    try {
                        return EncodedImage(
                            bytes = outputData.bytes,
                            mimeType = targetMimeType,
                            dimensions = targetDimensions,
                        )
                    } finally {
                        outputData.close()
                    }
                } finally {
                    outputImage.close()
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                if (throwable is UnsupportedImageCodecException) throw throwable
                throw ImageCodecException("Unable to transform prompt image with Skiko", throwable)
            } finally {
                surface.close()
            }
        } finally {
            source.close()
        }
    }
}

private val ImageMimeType.backgroundColor: Int
    get() = when (this) {
        ImageMimeType.Jpeg -> Color.WHITE
        ImageMimeType.Png,
        ImageMimeType.Gif,
        ImageMimeType.Webp,
        -> Color.TRANSPARENT
    }

private val ImageMimeType.skikoQuality: Int
    get() = when (this) {
        ImageMimeType.Jpeg -> 85
        ImageMimeType.Png,
        ImageMimeType.Gif,
        ImageMimeType.Webp,
        -> 100
    }

private fun ImageMimeType.toSkikoFormat(): EncodedImageFormat =
    when (this) {
        ImageMimeType.Png -> EncodedImageFormat.PNG
        ImageMimeType.Jpeg -> EncodedImageFormat.JPEG
        ImageMimeType.Webp -> EncodedImageFormat.WEBP
        ImageMimeType.Gif -> throw UnsupportedImageCodecException(this, "encode")
    }
