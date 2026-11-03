package io.github.bert0ns.cribrum.compress

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class JpegTest {
    private fun status(bytes: ByteArray) = readJpeg(bytes).status

    @Test
    fun `a plain 8-bit baseline jpeg is eligible, with no marker and nothing to lose`() {
        val info = readJpeg(jpeg(EXIF))

        assertEquals(JpegStatus.ELIGIBLE, info.status)
        assertFalse(info.gainMap)
        assertNull(info.marker)
        assertEquals(Losses(), info.losses)
        assertTrue(info.compressible(30))
    }

    @Test
    fun `extended and progressive 8-bit jpegs are eligible, a 12-bit or lossless one is not`() {
        assertEquals(JpegStatus.ELIGIBLE, status(jpeg(sof = sof(0xC1))))
        assertEquals(JpegStatus.ELIGIBLE, status(jpeg(sof = sof(0xC2))))
        assertEquals(JpegStatus.UNSUPPORTED, status(jpeg(sof = sof(0xC1, precision = 12))))
        assertEquals(JpegStatus.UNSUPPORTED, status(jpeg(sof = sof(0xC3))))
        assertEquals(JpegStatus.UNSUPPORTED, status(jpeg(sof = sof(0xC9))))
    }

    @Test
    fun `bytes that are not a jpeg are unsupported, whatever the name`() {
        assertEquals(JpegStatus.UNSUPPORTED, status(ascii("\u0089PNG\r\n\u001a\n") + ByteArray(32)))
        assertEquals(JpegStatus.UNSUPPORTED, status(ByteArray(0)))
    }

    @Test
    fun `the motion photo flag is found as an attribute and as an element, under any prefix`() {
        val flags = listOf(
            """<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MotionPhoto="1"/>""",
            """<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MicroVideo='1'/>""",
            """<rdf:Description xmlns:Cam="http://ns.google.com/photos/1.0/camera/"><Cam:MotionPhoto>1</Cam:MotionPhoto></rdf:Description>""",
            """<rdf:Description xmlns:x1="http://ns.google.com/photos/1.0/camera/"><x1:MicroVideo> 1 </x1:MicroVideo></rdf:Description>""",
        )
        for (flag in flags) assertEquals(flag, JpegStatus.MOTION_PHOTO, status(jpeg(EXIF, xmp(rdf(flag)))))
    }

    @Test
    fun `a motion photo flag set to 0, or a key that only starts like one, is not a motion photo`() {
        val others = listOf(
            """<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MotionPhoto="0"/>""",
            """<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MotionPhotoVersion="1"/>""",
            """<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"><GCamera:MicroVideoOffset>1</GCamera:MicroVideoOffset></rdf:Description>""",
        )
        for (other in others) assertEquals(other, JpegStatus.ELIGIBLE, status(jpeg(EXIF, xmp(rdf(other)))))
    }

    @Test
    fun `a SEF trailer with MotionPhoto_Data is a motion photo, and one without it is eligible`() {
        assertEquals(JpegStatus.SEF_MOTION_PHOTO, status(withSef(jpeg(EXIF), "Image_UTC_Data", "MotionPhoto_Data")))
        assertEquals(JpegStatus.ELIGIBLE, status(withSef(jpeg(EXIF), "Image_UTC_Data", "MCC_Data", "Camera_Capture_Mode_Info")))
    }

    @Test
    fun `an Ultra HDR file is eligible, but compressible only from API 34`() {
        for (file in listOf(ultraHdr(), ultraHdr(iso = true))) {
            val info = readJpeg(file)

            assertEquals(JpegStatus.ELIGIBLE, info.status)
            assertTrue(info.gainMap)
            assertFalse(info.compressible(33))
            assertTrue(info.compressible(34))
        }
    }

    @Test
    fun `an Ultra HDR file with a SEF trailer after the gain map is eligible`() {
        assertEquals(JpegStatus.ELIGIBLE, status(withSef(ultraHdr(), "Image_UTC_Data")))
    }

    @Test
    fun `the gain map markers without an MPF APP2 are no gain map`() {
        assertFalse(readJpeg(jpeg(EXIF, ISO_21496)).gainMap)
    }

    @Test
    fun `a container item that is neither Primary nor GainMap is not eligible, under any prefix`() {
        val directory = """<rdf:Description xmlns:GContainer="http://ns.google.com/photos/1.0/container/" xmlns:GItem="http://ns.google.com/photos/1.0/container/item/"><GContainer:Directory><rdf:Seq>""" +
            """<rdf:li rdf:parseType="Resource"><GContainer:Item GItem:Semantic="Primary" GItem:Mime="image/jpeg"/></rdf:li>""" +
            """<rdf:li rdf:parseType="Resource"><GContainer:Item><GItem:Semantic>Depth</GItem:Semantic></GContainer:Item></rdf:li>""" +
            """</rdf:Seq></GContainer:Directory></rdf:Description>"""

        assertEquals(JpegStatus.CONTAINER, status(jpeg(EXIF, xmp(rdf(directory)))))
    }

    @Test
    fun `unknown data after the image is not eligible`() {
        assertEquals(JpegStatus.TRAILING_DATA, status(jpeg(EXIF) + ascii("ftypmp42") + ByteArray(100)))
        // still ends in EOI, but MPF's last image does not end there
        assertEquals(JpegStatus.TRAILING_DATA, status(ultraHdr() + jpeg()))
        // a SEF trailer cut off leaves bytes that do not end in EOI
        assertEquals(JpegStatus.TRAILING_DATA, status(withSef(jpeg(EXIF) + ByteArray(10), "Image_UTC_Data")))
        // SEFT at the end with no directory to cut it off at
        assertEquals(JpegStatus.TRAILING_DATA, status(jpeg(EXIF) + ascii("\u0004\u0000\u0000\u0000SEFT")))
    }

    @Test
    fun `the marker is read with its keys, wherever it sits before SOS`() {
        val marker = app15("cribrum\u0000version=1\nlevel=balanced\ncompressed_at=2026-11-03T10:15:30Z\n")

        assertEquals(Marker(1, "balanced", "2026-11-03T10:15:30Z"), readJpeg(jpeg(EXIF, marker)).marker)
        assertEquals(Marker(1, "balanced", "2026-11-03T10:15:30Z"), readJpeg(jpeg(marker, EXIF, xmp(rdf("")))).marker)
    }

    @Test
    fun `a foreign APP15 is ignored, and the first Cribrum one is read`() {
        val foreign = app15("Q\u0000\u0001GraphicConverter")

        assertNull(readJpeg(jpeg(EXIF, foreign)).marker)
        val first = app15("cribrum\u0000version=1\nlevel=high\ncompressed_at=2026-01-01T00:00:00Z\n")
        val second = app15("cribrum\u0000version=1\nlevel=smallest\ncompressed_at=2026-02-01T00:00:00Z\n")
        assertEquals("high", readJpeg(jpeg(EXIF, foreign, first, second)).marker!!.level)
    }

    @Test
    fun `a marker without keys still marks the file as compressed`() {
        assertEquals(Marker(null, null, null), readJpeg(jpeg(EXIF, app15("cribrum\u0000"))).marker)
    }

    @Test
    fun `the marker segment is the ASCII lines of design md, about 70 bytes`() {
        val segment = markerSegment(CompressionLevel.BALANCED, Instant.parse("2026-11-03T10:15:30.123Z"))

        val expected = ascii("cribrum\u0000version=1\nlevel=balanced\ncompressed_at=2026-11-03T10:15:30Z\n")
        assertArrayEquals(segment(0xEF, expected), segment)
        assertEquals(72, segment.size)
    }

    @Test
    fun `the marker goes right after the Exif APP1 and reads back`() {
        val original = jpeg(EXIF, xmp(rdf("")))
        val marker = markerSegment(CompressionLevel.HIGH, Instant.parse("2026-11-03T10:15:30Z"))

        val marked = withMarker(original, marker)

        assertArrayEquals(concat(listOf(original.copyOfRange(0, 2 + EXIF.size), marker, original.copyOfRange(2 + EXIF.size, original.size))), marked)
        assertEquals(Marker(1, "high", "2026-11-03T10:15:30Z"), readJpeg(marked).marker)
        assertEquals(readJpeg(original).copy(marker = readJpeg(marked).marker), readJpeg(marked))
    }

    @Test
    fun `without Exif the marker goes after SOI, or after an APP0 JFIF that follows it`() {
        val marker = markerSegment(CompressionLevel.SMALLEST, Instant.parse("2026-11-03T10:15:30Z"))

        val bare = jpeg()
        assertArrayEquals(concat(listOf(bare.copyOfRange(0, 2), marker, bare.copyOfRange(2, bare.size))), withMarker(bare, marker))
        val jfif = jpeg(JFIF)
        val at = 2 + JFIF.size
        assertArrayEquals(concat(listOf(jfif.copyOfRange(0, at), marker, jfif.copyOfRange(at, jfif.size))), withMarker(jfif, marker))
    }

    @Test
    fun `the marker stays before the MPF APP2, and an Ultra HDR file keeps its gain map and stays eligible`() {
        val marked = withMarker(ultraHdr(), markerSegment(CompressionLevel.HIGH, Instant.parse("2026-11-03T10:15:30Z")))

        val info = readJpeg(marked)
        assertEquals("high", info.marker!!.level)
        assertTrue(info.gainMap)
        // MPF counts the gain map's offset from its own segment, so a segment before MPF does not move it
        assertEquals(JpegStatus.ELIGIBLE, info.status)
    }

    @Test
    fun `a marker that would land behind the MPF APP2 is refused`() {
        val mpfFirst = jpeg(mpf(listOf(0 to 0)), EXIF)

        assertThrows(IllegalArgumentException::class.java) { withMarker(mpfFirst, markerSegment(CompressionLevel.HIGH, Instant.EPOCH)) }
    }

    @Test
    fun `the loss flags name re-edit data, a depth map, XMP and C2PA`() {
        val c2pa = segment(0xEB, ascii("JP") + ByteArray(8))
        val file = withSef(jpeg(EXIF, xmp(rdf("")), c2pa), "PhotoEditor_Re_Edit_Data", "DualShot_DepthMap_1")

        assertEquals(Losses(reEdit = true, depthMap = true, xmp = true, c2pa = true), readJpeg(file).losses)
        assertEquals(Losses(), readJpeg(withSef(jpeg(EXIF), "Image_UTC_Data")).losses)
    }

    @Test
    fun `a 20 MB file is read from its headers and its last bytes only`() {
        val file = withSef(jpeg(EXIF, xmp(rdf("")), entropy = 20_000_000), "Image_UTC_Data")
        var read = 0L
        val counting = object : Bytes {
            override val size = file.size.toLong()
            override fun read(offset: Long, length: Int) = file.asBytes().read(offset, length).also { read += it.size }
        }

        assertEquals(JpegStatus.ELIGIBLE, readJpeg(counting).status)
        assertTrue("read $read bytes", read < 2_000)
    }
}
