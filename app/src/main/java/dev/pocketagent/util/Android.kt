package dev.pocketagent.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Replaces the file's contents in one step: the text goes to a sibling temp file, is synced to storage, then renamed
 * over the original. If anything fails on the way, the previous contents are still there.
 */
fun File.writeTextAtomic(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    try {
        tmp.outputStream().use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        tmp.delete()
    }
}

fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

object Images {
    /** Scales by [scale] (1.0 = unchanged) and encodes as JPEG, the smallest format Claude accepts for photos/screens. */
    fun jpeg(src: Bitmap, scale: Float = 1f, quality: Int = 80): ByteArray {
        val bmp = if (scale < 1f) {
            // At least one pixel each way: a 1x2000 image would otherwise scale to a width of zero and throw.
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
        } else src
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }

    /** Longest edge Claude uses without downscaling on its side. */
    const val MAX_EDGE = 1568
}

object Times {
    private val display = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm")
    private val dateOnly = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd")

    fun fmt(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        display.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun fmtDate(epochMs: Long, zone: ZoneId): String = dateOnly.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** Accepts ISO-8601 date-time with offset, local date-time, or a bare date (start of day). */
    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        val s = text.trim()
        runCatching { return OffsetDateTime.parse(s).atZoneSameInstant(zone) }
        runCatching { return ZonedDateTime.parse(s).withZoneSameInstant(zone) }
        runCatching { return LocalDateTime.parse(s).atZone(zone) }
        runCatching { return LocalDate.parse(s).atStartOfDay(zone) }
        return null
    }
}
