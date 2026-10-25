package com.immoral.location.core.route

import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import java.util.Locale

/**
 * GPX import / export for [RoutePlan]s.
 *
 * The parser is intentionally lenient: real GPX files come from Strava, OsmAnd, Google Earth
 * and friends, carrying namespace prefixes, extra attributes and metadata. We only care about
 * the coordinates, so anything that is not a `trkpt`, `rtept` or `wpt` element is ignored.
 * Invalid elements never abort the parse; they are skipped.
 */
object RouteIO {
    /** Matches `lat="..."` or `lat='...'` inside a tag's attribute list. */
    private val LAT_ATTR = Regex("""\blat\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    /** Matches `lon="..."` or `lon='...'` inside a tag's attribute list. */
    private val LON_ATTR = Regex("""\blon\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    /** Matches an elevation child element, with or without a namespace prefix. */
    private val ELE_ELEMENT =
        Regex("""<(?:[A-Za-z_][\w.\-]*:)?ele\s*>\s*([^<]*)</""", RegexOption.IGNORE_CASE)

    /** Matches the opening of any waypoint-bearing element, with or without a namespace prefix. */
    private val WAYPOINT_TAG = Regex(
        """<(?:[A-Za-z_][\w.\-]*:)?(?:trkpt|rtept|wpt)(?=[\s/>])([^>]*)>""",
        RegexOption.IGNORE_CASE,
    )

    /** Default altitude used when a waypoint has no elevation. */
    private const val DEFAULT_ALTITUDE = 50.0

    /**
     * Parses GPX XML into an ordered list of waypoints.
     *
     * `trkpt`, `rtept` and `wpt` elements are all accepted, in document order. Waypoints
     * without usable `lat`/`lon` attributes are skipped.
     *
     * @param xml GPX document text.
     * @return parsed points; empty when the document holds no valid waypoint.
     */
    fun parseGpx(xml: String): List<GeoPoint> {
        if (xml.isBlank()) return emptyList()
        val matches = WAYPOINT_TAG.findAll(xml).toList()
        if (matches.isEmpty()) return emptyList()
        val result = ArrayList<GeoPoint>(matches.size)
        for ((index, match) in matches.withIndex()) {
            val attrs = match.groupValues[1]
            val lat = parseDoubleAttribute(LAT_ATTR, attrs) ?: continue
            val lon = parseDoubleAttribute(LON_ATTR, attrs) ?: continue
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue
            val bodyEnd = matches.getOrNull(index + 1)?.range?.first ?: xml.length
            val body = xml.substring(match.range.last + 1, bodyEnd)
            val alt = parseElevation(body) ?: DEFAULT_ALTITUDE
            result.add(GeoPoint(lat = lat, lon = lon, alt = alt))
        }
        return result
    }

    /**
     * Serializes [points] as a GPX 1.1 track.
     *
     * @param name Track name written into `<metadata><name>`.
     * @return a standalone GPX document.
     */
    fun toGpx(points: List<GeoPoint>, name: String = "ImmoralLocation route"): String {
        val builder = StringBuilder(256 + points.size * 96)
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        builder.append("<gpx version=\"1.1\" creator=\"ImmoralLocation\" ")
        builder.append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        builder.append("  <metadata>\n")
        builder.append("    <name>").append(escapeXml(name)).append("</name>\n")
        builder.append("  </metadata>\n")
        builder.append("  <trk>\n")
        builder.append("    <name>").append(escapeXml(name)).append("</name>\n")
        builder.append("    <trkseg>\n")
        for (point in points) {
            builder.append("      <trkpt lat=\"").append(format(point.lat))
                .append("\" lon=\"").append(format(point.lon)).append("\">\n")
            builder.append("        <ele>").append(format(point.alt)).append("</ele>\n")
            builder.append("      </trkpt>\n")
        }
        builder.append("    </trkseg>\n")
        builder.append("  </trk>\n")
        builder.append("</gpx>\n")
        return builder.toString()
    }

    /**
     * Inserts intermediate points so no segment is longer than [maxSegmentMeters].
     *
     * Densified routes look natural when the simulated movement is rendered as a track:
     * consumers that sample at a fixed distance get a smooth line instead of long jumps.
     *
     * @param points Input waypoints.
     * @param maxSegmentMeters Upper bound for a single segment; values `<= 0` (or non-finite)
     *   disable densification and return [points] unchanged.
     * @return the densified polyline, keeping the original sequence order.
     */
    fun densify(points: List<GeoPoint>, maxSegmentMeters: Double = 25.0): List<GeoPoint> {
        if (points.size < 2) return points
        if (!maxSegmentMeters.isFinite() || maxSegmentMeters <= 0.0) return points
        val result = ArrayList<GeoPoint>(points.size * 2)
        result.add(points[0])
        for (i in 1 until points.size) {
            val from = points[i - 1]
            val to = points[i]
            val distance = GeoMath.distanceMeters(from, to)
            if (distance <= maxSegmentMeters) {
                result.add(to)
                continue
            }
            val steps = kotlin.math.ceil(distance / maxSegmentMeters).toInt()
            for (step in 1 until steps) {
                result.add(GeoMath.interpolate(from, to, step.toDouble() / steps))
            }
            result.add(to)
        }
        return result
    }

    private fun parseDoubleAttribute(regex: Regex, attrs: String): Double? {
        val raw = regex.find(attrs)?.groupValues?.get(1)?.trim() ?: return null
        if (raw.isEmpty()) return null
        return raw.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun parseElevation(body: String): Double? =
        ELE_ELEMENT.find(body)?.groupValues?.get(1)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun format(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun escapeXml(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}
