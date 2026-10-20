// Image metadata used by detectors and best-copy selection. Mirrors lab/src/cribrum_lab/metadata.py.
package io.github.bert0ns.cribrum.metadata

import java.time.LocalDateTime

/** [width] and [height] are as displayed, with the EXIF orientation already applied. */
data class ImageMetadata(
    val width: Int,
    val height: Int,
    val mime: String?,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    /** EXIF capture time, in the camera's local time. Used for bursts, never as an age signal. */
    val capturedAt: LocalDateTime? = null,
) {
    /** Screenshots and forwarded media usually have no camera EXIF. */
    val hasCameraExif: Boolean get() = cameraMake != null || cameraModel != null
}
