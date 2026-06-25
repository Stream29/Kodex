package io.github.stream29.codex.lite.utils.images.codec

import io.github.stream29.codex.lite.utils.images.EncodedImage
import io.github.stream29.codex.lite.utils.images.ImageMimeType
import io.github.stream29.codex.lite.utils.images.PromptImageTransformRequest
import io.github.stream29.codex.lite.utils.images.PromptImageTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

public actual val HostPromptImageTransformer: PromptImageTransformer = JvmImageIoPromptImageTransformer

public object JvmImageIoPromptImageTransformer : PromptImageTransformer {
    override suspend fun transform(request: PromptImageTransformRequest): EncodedImage = withContext(Dispatchers.IO) {
        ensureImageIoPluginsLoaded()
        val targetMimeType = request.plan.outputMimeType
        val targetDimensions = request.plan.outputDimensions
        val decoded = decode(request.sourceBytes)
        val resized = decoded.resizeTo(
            width = targetDimensions.width,
            height = targetDimensions.height,
            mimeType = targetMimeType,
        )
        EncodedImage(
            bytes = resized.encodeAs(targetMimeType),
            mimeType = targetMimeType,
            dimensions = targetDimensions,
        )
    }

    private fun decode(bytes: ByteArray): BufferedImage =
        try {
            ImageIO.read(ByteArrayInputStream(bytes))
                ?: throw ImageCodecException("Unable to decode image bytes with JVM ImageIO")
        } catch (error: IOException) {
            throw ImageCodecException("Unable to decode image bytes with JVM ImageIO", error)
        }

    private fun BufferedImage.resizeTo(
        width: Int,
        height: Int,
        mimeType: ImageMimeType,
    ): BufferedImage {
        val outputType = when (mimeType) {
            ImageMimeType.Jpeg -> BufferedImage.TYPE_INT_RGB
            ImageMimeType.Png,
            ImageMimeType.Webp,
            -> BufferedImage.TYPE_INT_ARGB

            ImageMimeType.Gif -> throw UnsupportedImageCodecException(mimeType, "encode")
        }
        val output = BufferedImage(width, height, outputType)
        val graphics = output.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (mimeType == ImageMimeType.Jpeg) {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
            }
            graphics.drawImage(this, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return output
    }

    private fun BufferedImage.encodeAs(mimeType: ImageMimeType): ByteArray {
        val writer = ImageIO.getImageWritersByMIMEType(mimeType.mime)
            .asSequence()
            .firstOrNull()
            ?: throw UnsupportedImageCodecException(mimeType, "encode")
        val output = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(output).use { imageOutput ->
                writer.output = imageOutput
                writer.write(null, IIOImage(this, null, null), writer.defaultWriteParam.configure(mimeType))
            }
        } catch (error: IOException) {
            throw ImageCodecException("Unable to encode image as ${mimeType.mime}", error)
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    private fun ImageWriteParam.configure(mimeType: ImageMimeType): ImageWriteParam {
        if (canWriteCompressed() && mimeType == ImageMimeType.Jpeg) {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.85f
        }
        return this
    }
}

private val imageIoPluginsLoaded: Unit by lazy {
    ImageIO.scanForPlugins()
}

private fun ensureImageIoPluginsLoaded() {
    imageIoPluginsLoaded
}
