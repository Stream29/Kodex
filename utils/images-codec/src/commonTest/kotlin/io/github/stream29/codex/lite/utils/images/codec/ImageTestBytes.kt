@file:OptIn(ExperimentalEncodingApi::class)

package io.github.stream29.codex.lite.utils.images.codec

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal fun pngBytes(width: Int, height: Int): ByteArray =
    byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x0d,
        0x49, 0x48, 0x44, 0x52,
    ) + width.toBigEndianBytes() + height.toBigEndianBytes()

private fun Int.toBigEndianBytes(): ByteArray =
    byteArrayOf(
        ((this ushr 24) and 0xff).toByte(),
        ((this ushr 16) and 0xff).toByte(),
        ((this ushr 8) and 0xff).toByte(),
        (this and 0xff).toByte(),
    )

internal val promptImagePng64x32: ByteArray
    get() = Base64.Default.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAAgklEQVR4Xu3QoRHDABADQeNgY+MUkf6rcC82XxKskcASgZ/5Oz7n9TQ7HNosgEObBXBoswAObf4GuH/faP6jBXCQB9P4jxbAQR5M4z9aAAd5MI3/aAEc5ME0/qMFcJAH0/iPFsBBHkzjP1oAB3kwjf9oARzaLIBDmwVwaLMADm3qA7y8LuS12WzThwAAAABJRU5ErkJggg==",
    )

internal val promptImageGif64x32: ByteArray
    get() = Base64.Default.decode(
        "R0lGODlhQAAgAPAAAAoUHtw8KCwAAAAAQAAgAEAIfgABCBxIsKDBgwgTKlxoMIDDhxAjSpxIseJDhgQtatzI0SHGjyBDihxJsiTDjihTXgSpsiVHkzBjypxJs6bNky5zUgyps2dEnj6DAg3a86bRo0iTKl3KtKnTpzSJ+hwq1SXVqiqvYkWpdetLll6zgg3LdSzZr1DTql3Ltu3BgAA7",
    )

internal val promptImageJpeg64x32: ByteArray
    get() = Base64.Default.decode(
        "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAgAEADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDxGiivWKzxOJ9hbS9zuy3LfrvN73Ly26X3v5rseT0V6xRXN/aX938f+Aep/q5/09/D/gnk9FesUUf2l/d/H/gB/q5/09/D/gnk9FesV5PXThsT7e+lrHl5llv1Ll97m5r9LbW833CvWK8nooxOG9vbW1gy3MvqXN7vNzW622v5PuesUV5PRXN/Zv8Ae/D/AIJ6n+sf/Tr8f+AesUV5PRR/Zv8Ae/D/AIIf6x/9Ovx/4B6xXk9FFdOGw3sL63ueXmWZfXeX3eXlv1vvbyXY/9k=",
    )
