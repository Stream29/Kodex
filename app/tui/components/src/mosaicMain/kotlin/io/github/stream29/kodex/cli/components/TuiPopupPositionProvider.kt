package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize

internal actual fun createAboveStartPopupPositionProvider(): TuiPopupPositionProvider =
    object : TuiPopupPositionProvider {
        override fun calculateMaximumSize(
            anchorBounds: TuiPopupAnchorBounds,
            surfaceSize: IntSize,
        ): IntSize = IntSize(
            width = surfaceSize.width,
            height = maxOf(
                anchorBounds.position.y,
                surfaceSize.height - anchorBounds.position.y - anchorBounds.size.height,
            ).coerceAtLeast(0),
        )

        override fun calculatePosition(
            anchorBounds: TuiPopupAnchorBounds,
            surfaceSize: IntSize,
            popupContentSize: IntSize,
        ): IntOffset {
            val maximumX = (surfaceSize.width - popupContentSize.width).coerceAtLeast(0)
            val maximumY = (surfaceSize.height - popupContentSize.height).coerceAtLeast(0)
            val aboveY = anchorBounds.position.y - popupContentSize.height
            val belowY = anchorBounds.position.y + anchorBounds.size.height
            val preferredY = when {
                aboveY >= 0 -> aboveY
                belowY <= maximumY -> belowY
                else -> aboveY
            }
            return IntOffset(
                x = anchorBounds.position.x.coerceIn(0, maximumX),
                y = preferredY.coerceIn(0, maximumY),
            )
        }
    }

internal actual fun createEndTopPopupPositionProvider(): TuiPopupPositionProvider =
    object : TuiPopupPositionProvider {
        override fun calculateMaximumSize(
            anchorBounds: TuiPopupAnchorBounds,
            surfaceSize: IntSize,
        ): IntSize = IntSize(
            width = maxOf(
                anchorBounds.position.x,
                surfaceSize.width - anchorBounds.position.x - anchorBounds.size.width,
            ).coerceAtLeast(0),
            height = surfaceSize.height,
        )

        override fun calculatePosition(
            anchorBounds: TuiPopupAnchorBounds,
            surfaceSize: IntSize,
            popupContentSize: IntSize,
        ): IntOffset {
            val maximumX = (surfaceSize.width - popupContentSize.width).coerceAtLeast(0)
            val maximumY = (surfaceSize.height - popupContentSize.height).coerceAtLeast(0)
            val endX = anchorBounds.position.x + anchorBounds.size.width
            val startX = anchorBounds.position.x - popupContentSize.width
            val preferredX = when {
                endX <= maximumX -> endX
                startX >= 0 -> startX
                else -> endX
            }
            return IntOffset(
                x = preferredX.coerceIn(0, maximumX),
                y = anchorBounds.position.y.coerceIn(0, maximumY),
            )
        }
    }
