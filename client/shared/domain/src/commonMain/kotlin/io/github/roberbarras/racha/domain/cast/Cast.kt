package io.github.roberbarras.racha.domain.cast

/** Where a video can be sent (H18): a Chromecast, a DLNA/UPnP TV or renderer. External displays are the platform's. */
enum class CastKind { CHROMECAST, DLNA }

/**
 * A device found on the local network. [address] is how to reach it: `host:port` for a Chromecast, the URL of its
 * description for DLNA. [id] is stable across searches (the Chromecast's id, the UPnP UDN).
 */
data class CastDevice(val id: String, val name: String, val kind: CastKind, val address: String, val model: String? = null) {
    /** The host the device is on, to choose which of this machine's addresses it can reach. */
    val host: String
        get() = when (kind) {
            CastKind.CHROMECAST -> address.substringBeforeLast(':')
            CastKind.DLNA -> address.substringAfter("://").substringBefore('/').substringBeforeLast(':')
        }
}

/** What TVs and Chromecasts understand from the files Racha plays. */
object CastMedia {
    private val types = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "avi" to "video/x-msvideo", "ts" to "video/mp2t", "m2ts" to "video/mp2t", "mpg" to "video/mpeg", "mpeg" to "video/mpeg",
        "wmv" to "video/x-ms-wmv", "flv" to "video/x-flv", "3gp" to "video/3gpp", "mp3" to "audio/mpeg", "m4a" to "audio/mp4",
        "aac" to "audio/aac", "ogg" to "audio/ogg", "opus" to "audio/ogg", "flac" to "audio/flac", "wav" to "audio/wav",
        "srt" to "application/x-subrip", "vtt" to "text/vtt",
    )

    fun contentType(name: String): String = types[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    /** SubRip to WebVTT (what Chromecasts read): a header, and `,` → `.` in the cue times; numbers and text stay. */
    fun srtToVtt(srt: String): String {
        val body = srt.replace("\r\n", "\n").replace('\r', '\n').removePrefix("﻿").lines().joinToString("\n") { line ->
            if ("-->" in line) line.replace(Regex("(\\d{1,2}:\\d{2}:\\d{2}),(\\d{1,3})"), "$1.$2") else line
        }
        return "WEBVTT\n\n" + body.trim() + "\n"
    }

    /** `H:MM:SS` as UPnP wants it (DLNA seek and positions). */
    fun upnpTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "${s / 3600}:${(s / 60 % 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** A UPnP time (`H:MM:SS`, `HH:MM:SS.mmm`, or `NOT_IMPLEMENTED`) in milliseconds; null if not a time. */
    fun parseUpnpTime(text: String?): Long? {
        val parts = text?.trim()?.split(':') ?: return null
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val sec = parts[2].toDoubleOrNull() ?: return null
        return ((h * 3600 + m * 60) * 1000 + (sec * 1000).toLong())
    }
}
