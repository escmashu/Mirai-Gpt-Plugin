package org.gptbot

import net.mamoe.mirai.message.data.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * 把 QQ 消息里的图片转成 OpenAI 兼容接口能吃的 data URL。
 *
 * 流程：拿到图片的外链（或本地缓存 file://）→ 下载字节 → 太大就等比缩小 / 降质量重编码 → base64。
 * 任何一步失败都返回 null，让调用方退化成纯文本，不影响正常对话。
 */
object Vision {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) mirai-gpt-agent"

    /** mirai 侧最大可下载字节，防止被超大图打爆内存 */
    private const val HARD_LIMIT = 12 * 1024 * 1024

    suspend fun toDataUrl(image: Image, maxBytes: Int, maxEdge: Int): String? {
        val raw = runCatching { fetch(image) }.getOrNull() ?: return null
        if (raw.isEmpty()) return null
        val bytes = runCatching {
            if (raw.size <= maxBytes && !needsShrink(raw, maxEdge)) raw
            else shrink(raw, maxBytes, maxEdge)
        }.getOrDefault(raw)
        if (bytes.size > maxBytes) return null
        return "data:${mimeOf(bytes)};base64," + Base64.getEncoder().encodeToString(bytes)
    }

    // ------------------------------------------------------------ 取字节

    private suspend fun fetch(image: Image): ByteArray? {
        // queryUrl 是 Image.Key 里的成员扩展函数，需要把 companion 作为接收者
        val url = runCatching { with(Image.Key) { image.queryUrl() } }.getOrNull()?.trim().orEmpty()
        if (url.isEmpty()) return null
        return when {
            url.startsWith("file:", true) -> File(URI(url)).let { if (it.exists()) it.readBytes() else null }
            url.startsWith("http", true) -> download(url)
            else -> null
        }
    }

    private fun download(url: String): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val len = conn.contentLength
            if (len > HARD_LIMIT) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream(len.coerceAtLeast(8192).coerceAtMost(HARD_LIMIT))
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > HARD_LIMIT) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ 压缩

    private fun needsShrink(raw: ByteArray, maxEdge: Int): Boolean {
        val size = imageSize(raw) ?: return false
        return size.first > maxEdge || size.second > maxEdge
    }

    /** 只读头部拿宽高，避免为了判断尺寸整张解码 */
    private fun imageSize(raw: ByteArray): Pair<Int, Int>? {
        val img = runCatching { ImageIO.read(ByteArrayInputStream(raw)) }.getOrNull() ?: return null
        return img.width to img.height
    }

    private fun shrink(raw: ByteArray, maxBytes: Int, maxEdge: Int): ByteArray {
        val src = ImageIO.read(ByteArrayInputStream(raw)) ?: return raw
        val longEdge = maxOf(src.width, src.height)
        val scale = if (longEdge > maxEdge) maxEdge.toDouble() / longEdge else 1.0
        val w = maxOf(1, (src.width * scale).toInt())
        val h = maxOf(1, (src.height * scale).toInt())

        val target = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = target.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        g.drawImage(src, 0, 0, w, h, java.awt.Color.WHITE, null)
        g.dispose()

        // 从 0.85 往下压，直到体积达标
        var quality = 0.85f
        var out = encodeJpeg(target, quality)
        while (out.size > maxBytes && quality > 0.4f) {
            quality -= 0.15f
            out = encodeJpeg(target, quality)
        }
        // 极端情况下再缩一半尺寸
        if (out.size > maxBytes) {
            val w2 = maxOf(1, w / 2)
            val h2 = maxOf(1, h / 2)
            val small = BufferedImage(w2, h2, BufferedImage.TYPE_INT_RGB)
            val g2 = small.createGraphics()
            g2.drawImage(target, 0, 0, w2, h2, java.awt.Color.WHITE, null)
            g2.dispose()
            out = encodeJpeg(small, 0.7f)
        }
        return out
    }

    private fun encodeJpeg(img: BufferedImage, quality: Float): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = quality
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    private fun mimeOf(b: ByteArray): String = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size > 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "image/gif"
        b.size > 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() -> "image/webp"
        b.size > 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "image/bmp"
        else -> "image/jpeg"
    }
}
