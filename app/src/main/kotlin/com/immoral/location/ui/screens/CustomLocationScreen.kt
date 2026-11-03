package com.immoral.location.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.immoral.location.R
import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.map.CoordSystem
import com.immoral.location.net.CoordinateParser
import com.immoral.location.net.PlaceResult
import com.immoral.location.net.SearchOutcome
import com.immoral.location.ui.components.Fmt
import com.immoral.location.ui.components.GroupRow
import com.immoral.location.ui.components.GroupTile
import com.immoral.location.ui.components.IconBadge
import com.immoral.location.ui.components.NavRow
import com.immoral.location.ui.components.SectionHeader
import com.immoral.location.ui.components.TextInputDialog
import com.immoral.location.ui.components.groupItemShape
import com.immoral.location.ui.theme.tabular
import kotlinx.coroutines.delay

/** The point the 自定义 page is about to use, with the label it will be remembered under. */
data class CustomTarget(
    val name: String,
    val point: GeoPoint,
    val detail: String? = null,
)

/** Callbacks of the 自定义 page. */
class CustomActions(
    val onSearch: (String, (SearchOutcome) -> Unit) -> Unit,
    val onPickOnMap: () -> Unit,
    val onStartHere: (GeoPoint, String) -> Unit,
    val onSaveFavorite: (String, GeoPoint) -> Unit,
    val onNavigateTo: (GeoPoint, String) -> Unit,
    val onShowOnMap: (GeoPoint) -> Unit,
)

/**
 * 自定义定位 — a first-class entry for "put me at this exact place" (issue #1).
 *
 * Three ways to name a place, in the order people reach for them: paste / type a coordinate (with
 * the coordinate system it came from, because a 高德 or 百度 coordinate fed in raw lands hundreds of
 * metres off), search a place by name, or pick it on the map. Whatever was chosen shows up in one
 * docked card at the bottom with the single primary action 在此开始模拟 and the secondary ones
 * (收藏 / 导航到这里 / 在地图中查看).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomLocationScreen(
    position: GeoPoint,
    target: CustomTarget?,
    onTargetChange: (CustomTarget?) -> Unit,
    snackbarHostState: SnackbarHostState,
    actions: CustomActions,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current

    var system by rememberSaveable { mutableStateOf(CoordSystem.WGS84) }
    var coordText by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<PlaceResult>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchedFor by remember { mutableStateOf("") }
    var naming by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    val coordLabel = stringResource(R.string.shell_coordinate_label)
    val parsed = remember(coordText) { CoordinateParser.parse(coordText.trim()) }
    val converted = parsed?.let { system.toWgs84(it) }

    // A valid coordinate *is* the answer: it becomes the target as soon as it parses, so there is no
    // extra 确定 step between typing and starting. Switching the coordinate system re-converts it.
    LaunchedEffect(converted) {
        if (converted != null) {
            onTargetChange(
                CustomTarget(
                    name = coordLabel,
                    point = converted,
                    detail = if (system == CoordSystem.WGS84) null else coordText.trim(),
                ),
            )
        }
    }

    // Live search, debounced; a coordinate typed into the search box is handled by the field above.
    LaunchedEffect(query, retry) {
        val text = query.trim()
        if (text.length < 2) {
            results = emptyList()
            searchError = null
            searching = false
            return@LaunchedEffect
        }
        delay(450)
        searching = true
        searchError = null
        actions.onSearch(text) { outcome ->
            if (query.trim() != text) return@onSearch
            searching = false
            searchedFor = text
            when (outcome) {
                is SearchOutcome.Success -> {
                    results = outcome.results
                    searchError = null
                }
                is SearchOutcome.Failure -> {
                    results = emptyList()
                    searchError = outcome.message
                }
            }
        }
        delay(8_000)
        if (searching && query.trim() == text) {
            searching = false
            searchError = null
            results = emptyList()
            searchedFor = text
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .testTag("e2e.custom"),
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.shell_tab_custom_title)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = target != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                val shown = target
                if (shown != null) {
                    TargetBar(
                        target = shown,
                        position = position,
                        onClear = {
                            onTargetChange(null)
                            coordText = ""
                        },
                        onStart = {
                            focus.clearFocus()
                            actions.onStartHere(shown.point, shown.name)
                        },
                        onSave = { naming = true },
                        onNavigate = { actions.onNavigateTo(shown.point, shown.name) },
                        onShowOnMap = { actions.onShowOnMap(shown.point) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // ------------------------------------------------------------ 坐标
            SectionHeader(title = stringResource(R.string.shell_section_coordinate))
            GroupTile(shape = groupItemShape(0, 1)) {
                Text(
                    text = stringResource(R.string.shell_coord_system),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                val systems = listOf(
                    CoordSystem.WGS84 to R.string.shell_coord_wgs,
                    CoordSystem.GCJ02 to R.string.shell_coord_gcj,
                    CoordSystem.BD09 to R.string.shell_coord_bd,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    systems.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = system == value,
                            onClick = { system = value },
                            shape = SegmentedButtonDefaults.itemShape(index, systems.size),
                            label = { Text(stringResource(label), maxLines = 1) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = coordText,
                    onValueChange = { coordText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("e2e.custom.coord"),
                    label = { Text(stringResource(R.string.shell_coord_field)) },
                    placeholder = { Text("39.908692, 116.397532") },
                    singleLine = true,
                    isError = coordText.isNotBlank() && parsed == null,
                    supportingText = {
                        Text(
                            text = when {
                                coordText.isBlank() -> stringResource(R.string.shell_coord_help)
                                parsed == null -> stringResource(R.string.search_bad_coord)
                                system == CoordSystem.WGS84 -> stringResource(R.string.shell_coord_ok)
                                else -> stringResource(
                                    R.string.shell_coord_converted,
                                    coordinateText(converted ?: parsed),
                                )
                            },
                        )
                    },
                    trailingIcon = {
                        if (coordText.isNotEmpty()) {
                            IconButton(onClick = { coordText = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                            }
                        } else {
                            IconButton(onClick = {
                                coordText = clipboard.getText()?.text?.trim().orEmpty()
                            }) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.shell_paste))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    shape = RoundedCornerShape(16.dp),
                )
            }

            // ------------------------------------------------------------ 搜索
            SectionHeader(
                title = stringResource(R.string.shell_section_search),
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("e2e.custom.search"),
                placeholder = { Text(stringResource(R.string.shell_search_place_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    when {
                        searching -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        query.isNotEmpty() -> IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                shape = RoundedCornerShape(28.dp),
            )
            AnimatedVisibility(
                visible = query.trim().length >= 2 && (results.isNotEmpty() || searchError != null || (!searching && searchedFor == query.trim())),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    when {
                        searchError != null -> GroupRow(
                            title = searchError.orEmpty(),
                            subtitleColor = MaterialTheme.colorScheme.error,
                            shape = groupItemShape(0, 1),
                            trailing = {
                                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.action_retry)) }
                            },
                        )
                        results.isEmpty() -> GroupRow(
                            title = stringResource(R.string.search_no_results),
                            shape = groupItemShape(0, 1),
                        )
                        else -> results.take(8).forEachIndexed { index, result ->
                            GroupRow(
                                title = result.name,
                                subtitle = result.address.ifBlank { coordinateText(result.point) },
                                shape = groupItemShape(index, results.take(8).size),
                                modifier = Modifier.testTag("e2e.custom.result.$index"),
                                leading = {
                                    IconBadge(
                                        icon = Icons.Filled.Place,
                                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        content = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {
                                    focus.clearFocus()
                                    onTargetChange(CustomTarget(result.name, result.point, result.address.ifBlank { null }))
                                },
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------------------ 其他方式
            SectionHeader(
                title = stringResource(R.string.shell_section_more_ways),
                modifier = Modifier.padding(top = 16.dp),
            )
            NavRow(
                title = stringResource(R.string.shell_pick_on_map),
                subtitle = stringResource(R.string.shell_pick_on_map_desc),
                icon = Icons.Filled.TouchApp,
                shape = groupItemShape(0, 2),
                onClick = actions.onPickOnMap,
                modifier = Modifier.testTag("e2e.custom.pick"),
            )
            Spacer(modifier = Modifier.height(2.dp))
            NavRow(
                title = stringResource(R.string.shell_use_current),
                subtitle = coordinateText(position),
                icon = Icons.Filled.MyLocation,
                shape = groupItemShape(1, 2),
                onClick = {
                    onTargetChange(CustomTarget(name = coordLabel, point = position))
                },
            )
        }
    }

    if (naming && target != null) {
        TextInputDialog(
            title = stringResource(R.string.shell_save_as_favorite),
            initial = if (target.name == coordLabel) "" else target.name,
            label = stringResource(R.string.favorites_name_hint),
            supporting = coordinateText(target.point),
            onConfirm = {
                actions.onSaveFavorite(it, target.point)
                naming = false
            },
            onDismiss = { naming = false },
        )
    }
}

/** The docked card with the chosen place and what can be done with it. */
@Composable
private fun TargetBar(
    target: CustomTarget,
    position: GeoPoint,
    onClear: () -> Unit,
    onStart: () -> Unit,
    onSave: () -> Unit,
    onNavigate: () -> Unit,
    onShowOnMap: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("e2e.custom.target"),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .imePadding()
                .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    icon = Icons.Filled.Place,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = target.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = coordinateText(target.point) + "  ·  " +
                            Fmt.distance(GeoMath.distanceMeters(position, target.point)),
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("e2e.custom.start"),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.shell_start_here), maxLines = 1)
                }
                FilledTonalIconButton(onClick = onSave, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.FavoriteBorder, contentDescription = stringResource(R.string.place_action_save))
                }
                FilledTonalIconButton(onClick = onNavigate, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.Directions, contentDescription = stringResource(R.string.place_action_navigate))
                }
                FilledTonalIconButton(onClick = onShowOnMap, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.Map, contentDescription = stringResource(R.string.shell_show_on_map))
                }
            }
        }
    }
}
