package io.github.stream29.codex.lite.utils.images.codec

import io.github.stream29.codex.lite.utils.images.EncodedImage
import io.github.stream29.codex.lite.utils.images.ImageMimeType
import io.github.stream29.codex.lite.utils.images.PromptImageTransformRequest
import io.github.stream29.codex.lite.utils.images.PromptImageTransformer
import korlibs.image.format.GIF
import korlibs.image.format.ImageDecodingProps
import korlibs.image.format.ImageEncodingProps
import korlibs.image.format.ImageFormats
import korlibs.image.format.PNG
import kotlinx.coroutines.CancellationException

private val promptImageDecodeFormats = ImageFormats(PNG, GIF)

/**
 * Stateless prompt-image transformer backed by KorIM's pure Kotlin PNG/GIF codecs.
 */
public object KorimPromptImageTransformer : PromptImageTransformer {
    override suspend fun transform(request: PromptImageTransformRequest): EncodedImage {
        val plan = request.plan
        if (plan.outputMimeType != ImageMimeType.Png) {
            throw UnsupportedImageCodecException(plan.outputMimeType, capability = "encode")
        }
        if (plan.source.mimeType != ImageMimeType.Png && plan.source.mimeType != ImageMimeType.Gif) {
            throw UnsupportedImageCodecException(plan.source.mimeType, capability = "decode")
        }

        val sourceBytes = request.sourceBytes
        val bitmap = try {
            promptImageDecodeFormats.decode(
                sourceBytes,
                ImageDecodingProps(
                    filename = "prompt-image",
                    tryNativeDecode = false,
                    format = promptImageDecodeFormats,
                ),
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            throw ImageCodecException("Unable to decode prompt image with KorIM", throwable)
        }

        val outputDimensions = plan.outputDimensions
        val outputBitmap = bitmap.toBMP32().let { bitmap32 ->
            if (bitmap32.width == outputDimensions.width && bitmap32.height == outputDimensions.height) {
                bitmap32
            } else {
                bitmap32.scaled(outputDimensions.width, outputDimensions.height)
            }
        }

        val outputBytes = try {
            PNG.encode(
                outputBitmap,
                ImageEncodingProps(filename = "prompt-image.png"),
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            throw ImageCodecException("Unable to encode prompt image as PNG with KorIM", throwable)
        }

        return EncodedImage(
            bytes = outputBytes,
            mimeType = ImageMimeType.Png,
            dimensions = outputDimensions,
        )
    }
}
