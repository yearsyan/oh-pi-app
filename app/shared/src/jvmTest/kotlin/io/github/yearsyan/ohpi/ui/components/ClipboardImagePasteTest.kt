package io.github.yearsyan.ohpi.ui.components

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClipboardImagePasteTest {
    @Test
    fun clipboardImageIsEncodedAsPngAttachment() {
        val source = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
        source.setRGB(1, 1, 0x7F3366CC)

        val result = encodeClipboardImage(source, name = "pasted.png")

        val success = assertIs<ImagePickResult.Success>(result)
        val attachment = success.images.single()
        assertEquals("image/png", attachment.mimeType)
        assertEquals("pasted.png", attachment.name)
        val decoded =
            ImageIO.read(
                ByteArrayInputStream(Base64.getDecoder().decode(attachment.data)),
            )
        assertEquals(3, decoded.width)
        assertEquals(2, decoded.height)
        assertEquals(source.getRGB(1, 1), decoded.getRGB(1, 1))
    }

    @Test
    fun clipboardImageHonorsAttachmentSizeLimit() {
        val source = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)

        val result = encodeClipboardImage(source, name = "pasted.png", maxBytes = 1)

        assertEquals(ImagePickResult.TooLarge, result)
    }
}
