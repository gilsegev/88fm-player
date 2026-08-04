package com.example.player88

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

class RssParser {
    fun parse(inputStream: InputStream): List<Episode> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) // Easier for basic parsing
        parser.setInput(inputStream, null)
        
        val episodes = mutableListOf<Episode>()
        
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "item") {
                episodes.add(readItem(parser))
            }
            eventType = parser.next()
        }
        
        return episodes.sortedByDescending { parseDate(it.pubDate) }
    }

    private fun readItem(parser: XmlPullParser): Episode {
        var id = ""
        var title = ""
        var audioUrl = ""
        var pubDate = ""
        var duration = 0L
        var imageUrl = ""

        while (!(parser.next() == XmlPullParser.END_TAG && parser.name == "item")) {
            if (parser.eventType != XmlPullParser.START_TAG) continue

            when (parser.name) {
                "guid", "omny:clipId" -> id = readText(parser)
                "title" -> title = readText(parser)
                "pubDate" -> pubDate = readText(parser)
                "enclosure" -> {
                    audioUrl = parser.getAttributeValue(null, "url") ?: ""
                    parser.next() // consume start tag
                }
                "itunes:duration", "duration" -> {
                    val durationStr = readText(parser)
                    duration = parseDuration(durationStr)
                }
                "itunes:image" -> {
                    imageUrl = parser.getAttributeValue(null, "href") ?: ""
                    parser.next() // consume start tag
                }
                "media:content" -> {
                    if (imageUrl.isEmpty()) {
                        imageUrl = parser.getAttributeValue(null, "url") ?: ""
                    }
                    parser.next()
                }
                else -> skip(parser)
            }
        }
        return Episode(id, title, audioUrl, pubDate, duration, imageUrl)
    }

    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text
            parser.nextTag()
        }
        return result
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) {
            throw IllegalStateException()
        }
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }

    private fun parseDuration(durationStr: String): Long {
        return try {
            if (durationStr.contains(":")) {
                val parts = durationStr.split(":").map { it.trim().toLong() }
                when (parts.size) {
                    3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                    2 -> parts[0] * 60 + parts[1]
                    else -> parts[0]
                }
            } else {
                durationStr.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseDate(dateStr: String): Long {
        val formats = listOf(
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        )
        for (format in formats) {
            try {
                return format.parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {}
        }
        return 0L
    }
}
