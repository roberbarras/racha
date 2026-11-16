package io.github.roberbarras.racha.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.Mode
import io.github.roberbarras.racha.domain.preferences.AppLanguage
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.IconBadge
import io.github.roberbarras.racha.ui.common.StorageAccessBanner
import io.github.roberbarras.racha.ui.common.categoryIcon
import io.github.roberbarras.racha.ui.common.categoryName
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RCard
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.RachaLogo
import io.github.roberbarras.racha.ui.design.SegmentedToggle
import io.github.roberbarras.racha.ui.folders.displayPath
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.add_folder
import io.github.roberbarras.racha.ui.resources.language_en
import io.github.roberbarras.racha.ui.resources.language_es
import io.github.roberbarras.racha.ui.resources.language_system
import io.github.roberbarras.racha.ui.resources.onboarding_adding
import io.github.roberbarras.racha.ui.resources.onboarding_back
import io.github.roberbarras.racha.ui.resources.onboarding_folders_message
import io.github.roberbarras.racha.ui.resources.onboarding_folders_title
import io.github.roberbarras.racha.ui.resources.onboarding_mode_leisure
import io.github.roberbarras.racha.ui.resources.onboarding_mode_leisure_hint
import io.github.roberbarras.racha.ui.resources.onboarding_mode_message
import io.github.roberbarras.racha.ui.resources.onboarding_mode_study
import io.github.roberbarras.racha.ui.resources.onboarding_mode_study_hint
import io.github.roberbarras.racha.ui.resources.onboarding_mode_title
import io.github.roberbarras.racha.ui.resources.onboarding_next
import io.github.roberbarras.racha.ui.resources.onboarding_reminder_message
import io.github.roberbarras.racha.ui.resources.onboarding_reminder_title
import io.github.roberbarras.racha.ui.resources.onboarding_skip
import io.github.roberbarras.racha.ui.resources.onboarding_start
import io.github.roberbarras.racha.ui.resources.onboarding_step
import io.github.roberbarras.racha.ui.resources.onboarding_sync_message
import io.github.roberbarras.racha.ui.resources.onboarding_sync_now
import io.github.roberbarras.racha.ui.resources.onboarding_sync_title
import io.github.roberbarras.racha.ui.resources.onboarding_welcome_message
import io.github.roberbarras.racha.ui.resources.onboarding_welcome_title
import io.github.roberbarras.racha.ui.resources.reminder_time
import io.github.roberbarras.racha.ui.resources.setting_notifications_dailyReminder
import io.github.roberbarras.racha.ui.settings.StepperRow
import io.github.roberbarras.racha.ui.settings.SwitchRow
import io.github.roberbarras.racha.ui.settings.shiftTime
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private enum class Step { WELCOME, MODE, FOLDERS, REMINDER, SYNC }

/**
 * First run (spec §10.3 "Onboarding"): language, what the app is for, the folders, a daily reminder and, optionally,
 * sync. Every step can be skipped and changed later; [onDone] with true opens Account.
 */
@Composable
fun OnboardingScreen(deps: RachaDependencies, onDone: (openAccount: Boolean) -> Unit) {
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    val steps = Step.entries.filter { it != Step.SYNC || deps.sync != null }
    val scope = rememberCoroutineScope()
    val finish: (Boolean) -> Unit = { account ->
        scope.launch {
            deps.preferences.set(Settings.OnboardingDone, true)
            onDone(account)
        }
    }
    Box(Modifier.fillMaxSize().background(Racha.colors.background).statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Racha.space.xl),
            verticalArrangement = Arrangement.spacedBy(Racha.space.l),
        ) {
            val index = steps.indexOf(step)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RText(stringResource(Res.string.onboarding_step, index + 1, steps.size), style = Racha.type.caption, color = Racha.colors.inkMuted, modifier = Modifier.weight(1f))
                Dots(steps.size, index)
            }
            when (step) {
                Step.WELCOME -> Welcome(deps)
                Step.MODE -> ModeStep(deps)
                Step.FOLDERS -> FoldersStep(deps)
                Step.REMINDER -> ReminderStep(deps)
                Step.SYNC -> Explained(stringResource(Res.string.onboarding_sync_title), stringResource(Res.string.onboarding_sync_message))
            }
            Spacer(Modifier.height(Racha.space.m))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                if (index > 0) RButton(stringResource(Res.string.onboarding_back), { step = steps[index - 1] }, kind = ButtonKind.Ghost)
                Box(Modifier.weight(1f))
                if (index < steps.lastIndex) {
                    RButton(stringResource(Res.string.onboarding_skip), { finish(false) }, kind = ButtonKind.Ghost)
                    RButton(stringResource(Res.string.onboarding_next), { step = steps[index + 1] })
                } else {
                    if (step == Step.SYNC) RButton(stringResource(Res.string.onboarding_sync_now), { finish(true) }, kind = ButtonKind.Secondary, icon = RachaIcons.Cloud)
                    RButton(stringResource(Res.string.onboarding_start), { finish(false) }, icon = RachaIcons.Play)
                }
            }
        }
    }
}

@Composable
private fun Dots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
        repeat(count) { i ->
            Box(Modifier.size(if (i == current) 10.dp else 8.dp).clip(CircleShape).background(if (i <= current) Racha.colors.accent else Racha.colors.borderStrong))
        }
    }
}

@Composable
private fun Explained(title: String, message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.s), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        RText(title, style = Racha.type.display)
        RText(message, style = Racha.type.body, color = Racha.colors.inkMuted)
    }
}

@Composable
private fun Welcome(deps: RachaDependencies) {
    Image(rememberVectorPainter(RachaLogo), contentDescription = null, modifier = Modifier.size(72.dp))
    Explained(stringResource(Res.string.onboarding_welcome_title), stringResource(Res.string.onboarding_welcome_message))
    val language by remember { deps.preferences.observe(Settings.Language) }.collectAsStateWithLifecycle(AppLanguage.SYSTEM)
    val scope = rememberCoroutineScope()
    SegmentedToggle(
        listOf(
            stringResource(Res.string.language_system) to AppLanguage.SYSTEM,
            stringResource(Res.string.language_es) to AppLanguage.ES,
            stringResource(Res.string.language_en) to AppLanguage.EN,
        ),
        language, { scope.launch { deps.preferences.set(Settings.Language, it) } },
    )
}

@Composable
private fun ModeStep(deps: RachaDependencies) {
    Explained(stringResource(Res.string.onboarding_mode_title), stringResource(Res.string.onboarding_mode_message))
    val mode by remember { deps.preferences.observe(Settings.Mode) }.collectAsStateWithLifecycle(Mode.STUDY)
    val scope = rememberCoroutineScope()
    listOf(
        Triple(Mode.STUDY, Res.string.onboarding_mode_study, Res.string.onboarding_mode_study_hint) to RachaIcons.GraduationCap,
        Triple(Mode.LEISURE, Res.string.onboarding_mode_leisure, Res.string.onboarding_mode_leisure_hint) to RachaIcons.Tv,
    ).forEach { (option, icon) ->
        val (value, title, hint) = option
        RCard(Modifier.fillMaxWidth(), onClick = { scope.launch { deps.setMode(value) } }, selected = mode == value, onClickLabel = stringResource(title)) {
            Row(Modifier.padding(Racha.space.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                IconBadge(icon)
                Column(verticalArrangement = Arrangement.spacedBy(Racha.space.xxs)) {
                    RText(stringResource(title), style = Racha.type.heading)
                    RText(stringResource(hint), style = Racha.type.body, color = Racha.colors.inkMuted)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoldersStep(deps: RachaDependencies) {
    Explained(stringResource(Res.string.onboarding_folders_title), stringResource(Res.string.onboarding_folders_message))
    val access by remember { deps.platform.storageAccess }.collectAsStateWithLifecycle(true)
    if (!access) StorageAccessBanner(onAllow = deps.platform::requestStorageAccess)
    val categories by remember { deps.categories.observeAll() }.collectAsStateWithLifecycle(emptyList())
    var roots by remember { mutableStateOf<Map<CategoryKey, List<CategoryRoot>>>(emptyMap()) }
    var adding by remember { mutableStateOf<CategoryKey?>(null) }
    val scope = rememberCoroutineScope()
    categories.sortedBy { it.order }.forEach { category ->
        RCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.m), verticalArrangement = Arrangement.spacedBy(Racha.space.s), itemVerticalAlignment = Alignment.CenterVertically) {
                    IconBadge(categoryIcon(category))
                    RText(categoryName(category), style = Racha.type.heading, modifier = Modifier.weight(1f))
                    RButton(
                        stringResource(if (adding == category.key) Res.string.onboarding_adding else Res.string.add_folder),
                        {
                            scope.launch {
                                val location = deps.platform.pickFolder() ?: return@launch
                                adding = category.key
                                try {
                                    deps.manageRoots.add(category.key, location)
                                    roots = roots + (category.key to deps.manageRoots.roots(category.key))
                                } finally {
                                    adding = null
                                }
                            }
                        },
                        kind = ButtonKind.Secondary, icon = RachaIcons.FolderPlus, enabled = adding == null,
                    )
                }
                roots[category.key].orEmpty().forEach { RText(displayPath(it.location), style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 2) }
            }
        }
    }
}

@Composable
private fun ReminderStep(deps: RachaDependencies) {
    Explained(stringResource(Res.string.onboarding_reminder_title), stringResource(Res.string.onboarding_reminder_message))
    val on by remember { deps.preferences.observe(Settings.DailyReminder) }.collectAsStateWithLifecycle(false)
    val time by remember { deps.preferences.observe(Settings.DailyReminderTime) }.collectAsStateWithLifecycle(Settings.DailyReminderTime.default)
    val allowed by remember { deps.platform.notificationsAllowed }.collectAsStateWithLifecycle(true)
    val scope = rememberCoroutineScope()
    RCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            SwitchRow(stringResource(Res.string.setting_notifications_dailyReminder), on, { enabled ->
                scope.launch { deps.preferences.set(Settings.DailyReminder, enabled) }
                if (enabled && !allowed) deps.platform.requestNotifications()
            })
            if (on) {
                StepperRow(
                    stringResource(Res.string.reminder_time), time,
                    onMinus = { scope.launch { deps.preferences.set(Settings.DailyReminderTime, shiftTime(time, -30)) } },
                    onPlus = { scope.launch { deps.preferences.set(Settings.DailyReminderTime, shiftTime(time, 30)) } },
                )
            }
        }
    }
}
