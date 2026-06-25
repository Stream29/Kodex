@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.codex.lite.utils.images.codec

import io.github.stream29.codex.lite.utils.images.EncodedImage
import io.github.stream29.codex.lite.utils.images.ImageMimeType
import io.github.stream29.codex.lite.utils.images.PromptImageTransformRequest
import io.github.stream29.codex.lite.utils.images.PromptImageTransformer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.gdiplus.GdipCreateBitmapFromScan0
import platform.gdiplus.GdipDeleteGraphics
import platform.gdiplus.GdipDisposeImage
import platform.gdiplus.GdipDrawImageRectI
import platform.gdiplus.GdipGetImageEncoders
import platform.gdiplus.GdipGetImageEncodersSize
import platform.gdiplus.GdipGetImageGraphicsContext
import platform.gdiplus.GdipGraphicsClear
import platform.gdiplus.GdipLoadImageFromStream
import platform.gdiplus.GdipSaveImageToStream
import platform.gdiplus.GdipSetInterpolationMode
import platform.gdiplus.GdipSetPixelOffsetMode
import platform.gdiplus.GdipSetSmoothingMode
import platform.gdiplus.GdiplusShutdown
import platform.gdiplus.GdiplusStartup
import platform.gdiplus.GdiplusStartupInput
import platform.gdiplus.ImageCodecInfo
import platform.gdiplus.ImageFormatJPEG
import platform.gdiplus.ImageFormatPNG
import platform.gdiplus.InterpolationModeHighQualityBicubic
import platform.gdiplus.Ok
import platform.gdiplus.PixelFormat32bppARGB
import platform.gdiplus.PixelOffsetModeHighQuality
import platform.gdiplus.SmoothingModeHighQuality
import platform.posix._GUID
import platform.posix.memcpy
import platform.windows.CreateStreamOnHGlobal
import platform.windows.FALSE
import platform.windows.GMEM_MOVEABLE
import platform.windows.GetHGlobalFromStream
import platform.windows.GlobalAlloc
import platform.windows.GlobalFree
import platform.windows.GlobalLock
import platform.windows.GlobalSize
import platform.windows.GlobalUnlock
import platform.windows.IStream
import platform.windows.S_OK
import platform.windows.TRUE

/**
 * Stateless prompt-image transformer backed by Windows GDI+.
 */
public object GdiPlusPromptImageTransformer : PromptImageTransformer {
    override suspend fun transform(request: PromptImageTransformRequest): EncodedImage =
        transformWithGdiPlus(request)

    private fun transformWithGdiPlus(request: PromptImageTransformRequest): EncodedImage {
        val plan = request.plan
        if (plan.outputMimeType !in gdiPlusEncodeMimeTypes) {
            throw UnsupportedImageCodecException(plan.outputMimeType, capability = "encode")
        }
        if (plan.source.mimeType !in gdiPlusDecodeMimeTypes) {
            throw UnsupportedImageCodecException(plan.source.mimeType, capability = "decode")
        }

        return memScoped {
            val token = alloc<ULongVar>()
            val startupInput = alloc<GdiplusStartupInput>().apply {
                GdiplusVersion = 1u
                DebugEventCallback = null
                SuppressBackgroundThread = FALSE
                SuppressExternalCodecs = FALSE
            }
            checkGdiPlusStatus(GdiplusStartup(token.ptr, startupInput.ptr, null), "startup")

            try {
                val sourceStream = request.sourceBytes.toInputStream()
                try {
                    val sourceImage = loadImage(sourceStream)
                    try {
                        val outputImage = createOutputImage(
                            sourceImage = sourceImage,
                            width = plan.outputDimensions.width,
                            height = plan.outputDimensions.height,
                            mimeType = plan.outputMimeType,
                        )
                        try {
                            val outputBytes = outputImage.encodeAs(plan.outputMimeType)
                            EncodedImage(
                                bytes = outputBytes,
                                mimeType = plan.outputMimeType,
                                dimensions = plan.outputDimensions,
                            )
                        } finally {
                            GdipDisposeImage(outputImage)
                        }
                    } finally {
                        GdipDisposeImage(sourceImage)
                    }
                } finally {
                    sourceStream.release()
                }
            } finally {
                GdiplusShutdown(token.value)
            }
        }
    }
}

/**
 * Windows host transformer that prefers native GDI+ and falls back to KorIM.
 */
public object WindowsNativePromptImageTransformer : PromptImageTransformer {
    override suspend fun transform(request: PromptImageTransformRequest): EncodedImage =
        try {
            GdiPlusPromptImageTransformer.transform(request)
        } catch (nativeFailure: UnsupportedImageCodecException) {
            request.fallbackToKorim(nativeFailure)
        } catch (nativeFailure: ImageCodecException) {
            request.fallbackToKorim(nativeFailure)
        }
}

private val gdiPlusDecodeMimeTypes: Set<ImageMimeType> =
    setOf(ImageMimeType.Png, ImageMimeType.Jpeg)

private val gdiPlusEncodeMimeTypes: Set<ImageMimeType> =
    setOf(ImageMimeType.Png, ImageMimeType.Jpeg)

private suspend fun PromptImageTransformRequest.fallbackToKorim(nativeFailure: Throwable): EncodedImage =
    try {
        KorimPromptImageTransformer.transform(this)
    } catch (fallbackFailure: Throwable) {
        fallbackFailure.addSuppressed(nativeFailure)
        throw fallbackFailure
    }

private fun checkGdiPlusStatus(status: UInt, operation: String) {
    if (status != Ok) {
        throw ImageCodecException("GDI+ $operation failed with status $status")
    }
}

private fun checkWindowsStatus(status: Int, operation: String) {
    if (status != S_OK) {
        throw ImageCodecException("Windows $operation failed with HRESULT $status")
    }
}

private fun ByteArray.toInputStream(): CPointer<IStream> {
    val handle = GlobalAlloc(GMEM_MOVEABLE.toUInt(), size.toULong())
        ?: throw ImageCodecException("Unable to allocate Windows global memory for image bytes")
    var handleOwnedByCaller = true

    try {
        val destination = GlobalLock(handle)
            ?: throw ImageCodecException("Unable to lock Windows global memory for image bytes")
        try {
            usePinned { pinned ->
                memcpy(destination, pinned.addressOf(0), size.convert())
            }
        } finally {
            GlobalUnlock(handle)
        }

        return memScoped {
            val stream = alloc<CPointerVar<IStream>>()
            checkWindowsStatus(CreateStreamOnHGlobal(handle, TRUE, stream.ptr), "CreateStreamOnHGlobal")
            handleOwnedByCaller = false
            stream.value ?: throw ImageCodecException("CreateStreamOnHGlobal returned a null stream")
        }
    } finally {
        if (handleOwnedByCaller) {
            GlobalFree(handle)
        }
    }
}

private fun loadImage(stream: CPointer<IStream>): COpaquePointer =
    memScoped {
        val image = alloc<COpaquePointerVar>()
        checkGdiPlusStatus(GdipLoadImageFromStream(stream, image.ptr), "decode")
        image.value ?: throw ImageCodecException("GDI+ returned a null decoded image")
    }

private fun createOutputImage(
    sourceImage: COpaquePointer,
    width: Int,
    height: Int,
    mimeType: ImageMimeType,
): COpaquePointer =
    memScoped {
        val outputImage = alloc<COpaquePointerVar>()
        checkGdiPlusStatus(
            GdipCreateBitmapFromScan0(width, height, 0, PixelFormat32bppARGB, null, outputImage.ptr),
            "create output bitmap",
        )
        val output = outputImage.value ?: throw ImageCodecException("GDI+ returned a null output bitmap")

        try {
            val graphics = alloc<COpaquePointerVar>()
            checkGdiPlusStatus(GdipGetImageGraphicsContext(output, graphics.ptr), "create graphics context")
            val graphicsPointer = graphics.value ?: throw ImageCodecException("GDI+ returned a null graphics context")
            try {
                checkGdiPlusStatus(GdipGraphicsClear(graphicsPointer, mimeType.backgroundColor), "clear bitmap")
                checkGdiPlusStatus(
                    GdipSetInterpolationMode(graphicsPointer, InterpolationModeHighQualityBicubic),
                    "set interpolation mode",
                )
                checkGdiPlusStatus(
                    GdipSetSmoothingMode(graphicsPointer, SmoothingModeHighQuality),
                    "set smoothing mode",
                )
                checkGdiPlusStatus(
                    GdipSetPixelOffsetMode(graphicsPointer, PixelOffsetModeHighQuality),
                    "set pixel offset mode",
                )
                checkGdiPlusStatus(
                    GdipDrawImageRectI(graphicsPointer, sourceImage, 0, 0, width, height),
                    "draw resized image",
                )
            } finally {
                GdipDeleteGraphics(graphicsPointer)
            }

            output
        } catch (throwable: Throwable) {
            GdipDisposeImage(output)
            throw throwable
        }
    }

private fun COpaquePointer.encodeAs(mimeType: ImageMimeType): ByteArray {
    val stream = createOutputStream()
    try {
        memScoped {
            val encoder = alloc<_GUID>()
            findEncoder(mimeType, encoder)
            checkGdiPlusStatus(GdipSaveImageToStream(this@encodeAs, stream, encoder.ptr, null), "encode")
        }
        return stream.toByteArray()
    } finally {
        stream.release()
    }
}

private fun createOutputStream(): CPointer<IStream> =
    memScoped {
        val stream = alloc<CPointerVar<IStream>>()
        checkWindowsStatus(CreateStreamOnHGlobal(null, TRUE, stream.ptr), "CreateStreamOnHGlobal")
        stream.value ?: throw ImageCodecException("CreateStreamOnHGlobal returned a null output stream")
    }

private fun CPointer<IStream>.toByteArray(): ByteArray =
    memScoped {
        val handle = alloc<COpaquePointerVar>()
        checkWindowsStatus(GetHGlobalFromStream(this@toByteArray, handle.ptr), "GetHGlobalFromStream")
        val globalHandle = handle.value ?: throw ImageCodecException("GetHGlobalFromStream returned a null handle")
        val size = GlobalSize(globalHandle)
        if (size > Int.MAX_VALUE.toULong()) {
            throw ImageCodecException("Encoded image is too large: $size bytes")
        }
        val source = GlobalLock(globalHandle)
            ?: throw ImageCodecException("Unable to lock encoded image memory")
        try {
            val output = ByteArray(size.toInt())
            output.usePinned { pinned ->
                memcpy(pinned.addressOf(0), source, size.convert())
            }
            output
        } finally {
            GlobalUnlock(globalHandle)
        }
    }

private fun findEncoder(mimeType: ImageMimeType, destination: _GUID) {
    memScoped {
        val count = alloc<UIntVar>()
        val size = alloc<UIntVar>()
        checkGdiPlusStatus(GdipGetImageEncodersSize(count.ptr, size.ptr), "get encoders size")
        if (count.value == 0u || size.value == 0u) {
            throw UnsupportedImageCodecException(mimeType, capability = "encode")
        }

        val buffer = allocArray<ByteVar>(size.value.toInt()).reinterpret<ImageCodecInfo>()
        checkGdiPlusStatus(GdipGetImageEncoders(count.value, size.value, buffer), "get encoders")
        val targetFormat = mimeType.toGdiPlusFormat()
        repeat(count.value.toInt()) { index ->
            val codec = buffer[index]
            if (codec.FormatID.contentEquals(targetFormat)) {
                destination.copyFrom(codec.Clsid)
                return
            }
        }
        throw UnsupportedImageCodecException(mimeType, capability = "encode")
    }
}

private fun _GUID.copyFrom(other: _GUID) {
    Data1 = other.Data1
    Data2 = other.Data2
    Data3 = other.Data3
    repeat(GuidData4Size) { index ->
        Data4[index] = other.Data4[index]
    }
}

private fun _GUID.contentEquals(other: _GUID): Boolean =
    Data1 == other.Data1 &&
        Data2 == other.Data2 &&
        Data3 == other.Data3 &&
        (0 until GuidData4Size).all { index -> Data4[index] == other.Data4[index] }

private fun CPointer<IStream>.release() {
    pointed.lpVtbl?.pointed?.Release?.invoke(this)
}

private fun ImageMimeType.toGdiPlusFormat(): _GUID =
    when (this) {
        ImageMimeType.Png -> ImageFormatPNG
        ImageMimeType.Jpeg -> ImageFormatJPEG
        ImageMimeType.Gif,
        ImageMimeType.Webp,
        -> throw UnsupportedImageCodecException(this, capability = "encode")
    }

private val ImageMimeType.backgroundColor: UInt
    get() = when (this) {
        ImageMimeType.Jpeg -> 0xffffffffu
        ImageMimeType.Png,
        ImageMimeType.Gif,
        ImageMimeType.Webp,
        -> 0x00000000u
    }

private const val GuidData4Size: Int = 8
