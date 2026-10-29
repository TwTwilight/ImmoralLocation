package com.immoral.location.ui.map

import android.content.Context
import android.util.Log
import com.immoral.location.map.TilePreset
import com.immoral.location.map.TileSources
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * osmdroid bootstrap.
 *
 * osmdroid needs a user agent (the OSM tile policy rejects unknown clients) and a writable tile
 * cache before the first [org.osmdroid.views.MapView] is constructed. Both are configured from
 * `Application.onCreate` so no activity can accidentally create a map too early.
 */
object OsmdroidSetup {
    private const val TAG = "OsmdroidSetup"

    fun initialize(context: Context) {
        try {
            val config = Configuration.getInstance()
            // Must happen before anything touches the tile provider.
            config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            config.userAgentValue = context.packageName
            val base = File(context.filesDir, "osmdroid")
            if (!base.exists()) base.mkdirs()
            config.osmdroidBasePath = base
            config.osmdroidTileCache = File(base, "tile")
        } catch (t: Throwable) {
            Log.w(TAG, "osmdroid initialisation failed; the map will retry lazily", t)
        }
    }
}

/**
 * An XYZ/WMTS tile source driven by a resolved endpoint.
 *
 * Placeholders: `{z}`, `{x}`, `{y}` and `{s}` (rotated through the preset's sub-domain list, e.g.
 * `webrd01..04` for 高德 or `t0..t7` for 天地图). The endpoint comes from [TileSources.resolve], so
 * it is either a built-in preset (高德/腾讯/天地图/OSM) or the user's own `{z}/{x}/{y}` template —
 * never a key lifted from another app.
 */
class TemplateTileSource(
    private val template: String,
    private val subdomains: List<String> = emptyList(),
) : OnlineTileSourceBase(
    NAME,
    MIN_ZOOM,
    MAX_ZOOM,
    TILE_SIZE_PX,
    "",
    arrayOf(""),
    COPYRIGHT,
) {

    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        var url = template
            .replace("{z}", zoom.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        if (subdomains.isNotEmpty()) {
            val index = ((x + y) and 0x7) % subdomains.size
            url = url.replace("{s}", subdomains[index])
        }
        return url
    }

    override fun toString(): String = "$NAME[$template]"

    companion object {
        const val NAME: String = "ImmoralLocation"
        const val MIN_ZOOM: Int = 3
        const val MAX_ZOOM: Int = 19
        const val TILE_SIZE_PX: Int = 256
        const val COPYRIGHT: String = "© 高德 / 腾讯 / 天地图 / OpenStreetMap contributors"

        /** Convenience for callers that only have the persisted preset id. */
        fun of(preset: TilePreset, customTemplate: String, tiandituKey: String): TemplateTileSource? {
            val resolved = TileSources.resolve(preset, customTemplate, tiandituKey) ?: return null
            return TemplateTileSource(resolved.template, resolved.subdomains)
        }
    }
}
