@file:Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package io.github.stream29.codex.lite.utils.images.codec

import io.github.stream29.codex.lite.utils.images.EncodedImage
import io.github.stream29.codex.lite.utils.images.ImageMimeType
import io.github.stream29.codex.lite.utils.images.PromptImageTransformRequest
import io.github.stream29.codex.lite.utils.images.PromptImageTransformer
import js.buffer.ArrayBuffer
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
import js.typedarrays.toByteArray
import js.typedarrays.toUint8Array
import kotlin.js.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await

public actual val HostPromptImageTransformer: PromptImageTransformer = SharpPromptImageTransformer

public object SharpPromptImageTransformer : PromptImageTransformer {
    override suspend fun transform(request: PromptImageTransformRequest): EncodedImage {
        val plan = request.plan
        val pipeline = sharp(request.sourceBytes.toUint8Array())
            .resize(
                plan.outputDimensions.width,
                plan.outputDimensions.height,
                unsafeJso<SharpResizeOptions> {
                    fit = "fill"
                },
            )
            .keepMetadata()
            .encodeAs(plan.outputMimeType)

        val outputBytes = try {
            pipeline.toBuffer().await().toByteArray()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            throw ImageCodecException("Unable to transform prompt image with sharp", throwable)
        }

        return EncodedImage(
            bytes = outputBytes,
            mimeType = plan.outputMimeType,
            dimensions = plan.outputDimensions,
        )
    }

    private fun SharpPipeline.encodeAs(mimeType: ImageMimeType): SharpPipeline =
        when (mimeType) {
            ImageMimeType.Png -> png()
            ImageMimeType.Jpeg -> jpeg(
                unsafeJso<SharpJpegOptions> {
                    quality = 85
                },
            )
            ImageMimeType.Webp -> webp(
                unsafeJso<SharpWebpOptions> {
                    lossless = true
                },
            )
            ImageMimeType.Gif -> throw UnsupportedImageCodecException(mimeType, "encode")
        }
}

@JsModule("sharp")
@JsNonModule
private external fun sharp(input: Uint8Array<ArrayBuffer>): SharpPipeline

private external interface SharpPipeline {
    fun resize(
        width: Int,
        height: Int,
        options: SharpResizeOptions = definedExternally,
    ): SharpPipeline

    fun keepMetadata(): SharpPipeline

    fun png(): SharpPipeline

    fun jpeg(options: SharpJpegOptions = definedExternally): SharpPipeline

    fun webp(options: SharpWebpOptions = definedExternally): SharpPipeline

    fun toBuffer(): Promise<Uint8Array<ArrayBuffer>>
}

private external interface SharpResizeOptions {
    var fit: String
}

private external interface SharpJpegOptions {
    var quality: Int
}

private external interface SharpWebpOptions {
    var lossless: Boolean
}
