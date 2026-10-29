package com.immoral.location.map

import com.immoral.location.R

/** Coordinate system a tile source is published in. */
enum class TileCoordSystem {
    /** OSM / 天地图 (CGCS2000 ≈ WGS-84): no conversion needed. */
    WGS84,

    /** 高德 / 腾讯: GCJ-02, needs the 300–500 m offset correction. */
    GCJ02,
}

/**
 * A concrete tile endpoint plus the coordinate system it uses.
 *
 * `{s}` in [template] is rotated through [subdomains]; `{key}` is replaced with the user's
 * 天地图 key. No provider SDK keys are embedded: 高德/腾讯 raster endpoints need none, and 天地图
 * uses the user's own免费 key (their keys are not package-bound, so this stays legit).
 */
data class ResolvedTiles(
    val labelRes: Int,
    val shortLabelRes: Int,
    val template: String,
    val coordSystem: TileCoordSystem,
    val subdomains: List<String>,
) {
    val isGcj02: Boolean get() = coordSystem == TileCoordSystem.GCJ02
}

/** The tile presets offered in the map/settings UI. */
enum class TilePreset(val id: String, val labelRes: Int, val shortLabelRes: Int) {
    /** 高德 raster tiles — Chinese labels, fast in mainland China. Default. */
    AMAP("amap", R.string.tile_preset_amap, R.string.tile_preset_amap_short),

    /** 腾讯 raster tiles. */
    TENCENT("tencent", R.string.tile_preset_tencent, R.string.tile_preset_tencent_short),

    /** 天地图 official WMTS — requires the user's own free key. */
    TIANDITU("tianditu", R.string.tile_preset_tianditu, R.string.tile_preset_tianditu_short),

    /** OpenStreetMap carto (local names, worldwide). */
    OSM("osm", R.string.tile_preset_osm, R.string.tile_preset_osm_short),

    /** User-provided `{z}/{x}/{y}` template. */
    CUSTOM("custom", R.string.tile_preset_custom, R.string.tile_preset_custom_short),
    ;

    companion object {
        fun fromId(id: String?): TilePreset =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AMAP
    }
}

/** Resolves the active preset + user inputs into a usable tile endpoint. */
object TileSources {

    /** Default preset: 高德, because the product targets mainland users. */
    val DEFAULT_PRESET: TilePreset = TilePreset.AMAP

    /** Fallback custom template (used when the user picked 自定义 but left the field empty). */
    const val FALLBACK_TEMPLATE: String = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

    /**
     * @param preset selected preset
     * @param customTemplate the user's `{z}/{x}/{y}` template (used by [TilePreset.CUSTOM])
     * @param tiandituKey the user's 天地图 key
     * @return the tile endpoint to use, or `null` when the preset needs a key that is missing.
     */
    fun resolve(
        preset: TilePreset,
        customTemplate: String,
        tiandituKey: String,
    ): ResolvedTiles? = when (preset) {
        TilePreset.AMAP -> ResolvedTiles(
            labelRes = preset.labelRes,
            shortLabelRes = preset.shortLabelRes,
            template = "https://webrd0{s}.is.autonavi.com/appmaptile" +
                "?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
            coordSystem = TileCoordSystem.GCJ02,
            subdomains = listOf("1", "2", "3", "4"),
        )

        TilePreset.TENCENT -> ResolvedTiles(
            labelRes = preset.labelRes,
            shortLabelRes = preset.shortLabelRes,
            template = "https://rt{s}.map.gtimg.com/tile?z={z}&x={x}&y={y}&type=3&styleid=1000",
            coordSystem = TileCoordSystem.GCJ02,
            subdomains = listOf("0", "1", "2", "3"),
        )

        TilePreset.TIANDITU -> {
            val key = tiandituKey.trim()
            if (key.isEmpty()) {
                null
            } else {
                ResolvedTiles(
                    labelRes = preset.labelRes,
                    shortLabelRes = preset.shortLabelRes,
                    template = "https://t{s}.tianditu.gov.cn/vec_w/wmts?SERVICE=WMTS&REQUEST=GetTile" +
                        "&VERSION=1.0.0&LAYER=vec&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles" +
                        "&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=$key",
                    // 天地图 serves CGCS2000, which is within a metre of WGS-84.
                    coordSystem = TileCoordSystem.WGS84,
                    subdomains = listOf("0", "1", "2", "3", "4", "5", "6", "7"),
                )
            }
        }

        TilePreset.OSM -> ResolvedTiles(
            labelRes = preset.labelRes,
            shortLabelRes = preset.shortLabelRes,
            template = FALLBACK_TEMPLATE,
            coordSystem = TileCoordSystem.WGS84,
            subdomains = emptyList(),
        )

        TilePreset.CUSTOM -> ResolvedTiles(
            labelRes = preset.labelRes,
            shortLabelRes = preset.shortLabelRes,
            template = customTemplate.trim().ifBlank { FALLBACK_TEMPLATE },
            coordSystem = TileCoordSystem.WGS84,
            subdomains = emptyList(),
        )
    }
}
