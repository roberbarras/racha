package io.github.roberbarras.racha.ui.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import io.github.roberbarras.racha.ui.design.RachaLogo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.application.browse.CategorySummary
import io.github.roberbarras.racha.application.library.scan.ScanStatus
import io.github.roberbarras.racha.ui.common.categoryName
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.StreakMeter
import io.github.roberbarras.racha.ui.design.interactive
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.app_name
import io.github.roberbarras.racha.ui.resources.library_up_to_date
import io.github.roberbarras.racha.ui.resources.nav_categories
import io.github.roberbarras.racha.ui.resources.nav_folders
import io.github.roberbarras.racha.ui.resources.nav_history
import io.github.roberbarras.racha.ui.resources.nav_home
import io.github.roberbarras.racha.ui.resources.nav_main
import io.github.roberbarras.racha.ui.resources.nav_my_list
import io.github.roberbarras.racha.ui.resources.nav_search
import io.github.roberbarras.racha.ui.resources.scan_shortcut
import io.github.roberbarras.racha.ui.resources.scanning
import io.github.roberbarras.racha.ui.resources.streak_days
import io.github.roberbarras.racha.ui.resources.streak_none
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

enum class Destination(val icon: ImageVector, val label: StringResource) {
    Home(RachaIcons.House, Res.string.nav_home),
    MyList(RachaIcons.Bookmark, Res.string.nav_my_list),
    History(RachaIcons.History, Res.string.nav_history),
    Search(RachaIcons.Search, Res.string.nav_search),
    Folders(RachaIcons.FolderOpen, Res.string.nav_folders),
}

enum class WindowClass { Compact, Medium, Expanded }

/** Navigation chrome by width: sidebar (desktop), dark rail (tablet), bottom bar (phone). */
@Composable
fun RachaShell(
    current: Destination?,
    onNavigate: (Destination) -> Unit,
    categories: List<CategorySummary>,
    onCategory: (CategorySummary) -> Unit,
    streakDays: Int,
    scan: ScanStatus,
    onScan: () -> Unit,
    content: @Composable (WindowClass) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Racha.colors.background)) {
        val windowClass = when {
            maxWidth < Racha.layout.compactMaxWidth -> WindowClass.Compact
            maxWidth < Racha.layout.expandedMinWidth -> WindowClass.Medium
            else -> WindowClass.Expanded
        }
        when (windowClass) {
            WindowClass.Expanded -> Row(Modifier.fillMaxSize()) {
                Sidebar(current, onNavigate, categories, onCategory, streakDays, scan, onScan)
                Box(Modifier.weight(1f).fillMaxHeight().statusBarsPadding().navigationBarsPadding()) { content(windowClass) }
            }
            WindowClass.Medium -> Row(Modifier.fillMaxSize()) {
                Rail(current, onNavigate, streakDays)
                Box(Modifier.weight(1f).fillMaxHeight().statusBarsPadding().navigationBarsPadding()) { content(windowClass) }
            }
            WindowClass.Compact -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) { content(windowClass) }
                BottomBar(current, onNavigate)
            }
        }
    }
}

@Composable
private fun Wordmark(onDark: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        Image(rememberVectorPainter(RachaLogo), contentDescription = null, modifier = Modifier.size(36.dp))
        RText(stringResource(Res.string.app_name), style = Racha.type.title, color = if (onDark) Racha.colors.onRail else Racha.colors.ink)
    }
}

@Composable
private fun Sidebar(
    current: Destination?,
    onNavigate: (Destination) -> Unit,
    categories: List<CategorySummary>,
    onCategory: (CategorySummary) -> Unit,
    streakDays: Int,
    scan: ScanStatus,
    onScan: () -> Unit,
) {
    val colors = Racha.colors
    Column(
        Modifier.width(Racha.layout.sidebarWidth).fillMaxHeight().background(colors.surface)
            .statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = Racha.space.l, vertical = Racha.space.xl),
    ) {
        Wordmark(onDark = false)
        Column(Modifier.padding(vertical = Racha.space.xl), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RText(
                    if (streakDays > 0) stringResource(Res.string.streak_days, streakDays) else stringResource(Res.string.streak_none),
                    style = Racha.type.bodyStrong,
                    modifier = Modifier.weight(1f),
                )
                RIcon(RachaIcons.Flame, null, tint = if (streakDays > 0) colors.accent else colors.inkSubtle, size = 18.dp)
            }
            StreakMeter(streakDays)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SidebarLabel(stringResource(Res.string.nav_main))
            Destination.entries.forEach { d ->
                SidebarItem(d.icon, stringResource(d.label), selected = current == d) { onNavigate(d) }
            }
            if (categories.isNotEmpty()) {
                SidebarLabel(stringResource(Res.string.nav_categories), Modifier.padding(top = Racha.space.xl))
                categories.forEach { c ->
                    SidebarItem(null, categoryName(c.category), selected = false, badge = c.itemCount.toString()) { onCategory(c) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Racha.radius.m)).background(colors.surfaceSunken)
                .interactive(onScan, RoundedCornerShape(Racha.radius.m)).padding(Racha.space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Racha.space.s),
        ) {
            RIcon(RachaIcons.RefreshCw, null, tint = colors.inkMuted, size = 16.dp)
            RText(
                if (scan.running) stringResource(Res.string.scanning) else "${stringResource(Res.string.library_up_to_date)} · ${stringResource(Res.string.scan_shortcut)}",
                style = Racha.type.caption,
                color = colors.inkMuted,
            )
        }
    }
}

@Composable
private fun SidebarLabel(text: String, modifier: Modifier = Modifier) {
    RText(text.uppercase(), modifier.padding(bottom = Racha.space.s), style = Racha.type.overline, color = Racha.colors.inkSubtle)
}

@Composable
private fun SidebarItem(icon: ImageVector?, label: String, selected: Boolean, badge: String? = null, onClick: () -> Unit) {
    val colors = Racha.colors
    val shape = RoundedCornerShape(Racha.radius.m)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(shape).background(if (selected) colors.surfaceSunken else Color.Transparent)
            .interactive(onClick, shape, role = Role.Tab).semantics { this.selected = selected }
            .padding(horizontal = Racha.space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        if (selected) Box(Modifier.width(3.dp).height(20.dp).clip(RoundedCornerShape(Racha.radius.pill)).background(colors.accent))
        if (icon != null) RIcon(icon, null, tint = if (selected) colors.ink else colors.inkMuted)
        RText(label, style = if (selected) Racha.type.bodyStrong else Racha.type.body, modifier = Modifier.weight(1f), maxLines = 1)
        if (badge != null) RText(badge, style = Racha.type.caption, color = colors.inkSubtle)
    }
}

@Composable
private fun Rail(current: Destination?, onNavigate: (Destination) -> Unit, streakDays: Int) {
    val colors = Racha.colors
    Column(
        Modifier.width(Racha.layout.railWidth).fillMaxHeight().background(colors.rail).statusBarsPadding().navigationBarsPadding()
            .padding(vertical = Racha.space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Racha.space.s),
    ) {
        Image(rememberVectorPainter(RachaLogo), contentDescription = stringResource(Res.string.app_name), modifier = Modifier.size(48.dp))
        Box(Modifier.height(Racha.space.l))
        Destination.entries.forEach { d ->
            val selected = current == d
            val shape = RoundedCornerShape(Racha.radius.l)
            Column(
                Modifier.width(72.dp).clip(shape).background(if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
                    .interactive({ onNavigate(d) }, shape, role = Role.Tab, overlay = colors.onRail).semantics { this.selected = selected }
                    .padding(vertical = Racha.space.s),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RIcon(d.icon, null, tint = if (selected) colors.accent else colors.onRailMuted)
                RText(stringResource(d.label), style = Racha.type.caption, color = if (selected) colors.onRail else colors.onRailMuted, maxLines = 1)
            }
        }
        Box(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RIcon(RachaIcons.Flame, null, tint = if (streakDays > 0) colors.accent else colors.onRailMuted)
            RText(streakDays.toString(), style = Racha.type.label, color = colors.onRail)
        }
    }
}

@Composable
private fun BottomBar(current: Destination?, onNavigate: (Destination) -> Unit) {
    val colors = Racha.colors
    Row(
        Modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding().padding(vertical = Racha.space.xs),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Destination.entries.forEach { d ->
            val selected = current == d
            Column(
                Modifier.weight(1f).interactive({ onNavigate(d) }, RoundedCornerShape(Racha.radius.m), role = Role.Tab)
                    .semantics { this.selected = selected }.padding(vertical = Racha.space.s),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RIcon(d.icon, null, tint = if (selected) colors.accent else colors.inkMuted)
                RText(stringResource(d.label), style = Racha.type.caption, color = if (selected) colors.ink else colors.inkMuted, maxLines = 1)
            }
        }
    }
}
