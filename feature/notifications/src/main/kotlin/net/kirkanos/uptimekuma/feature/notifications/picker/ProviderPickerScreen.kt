package net.kirkanos.uptimekuma.feature.notifications.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.kirkanos.uptimekuma.core.domain.notification.NotificationProviderSpec
import net.kirkanos.uptimekuma.core.domain.notification.ProviderCategory
import net.kirkanos.uptimekuma.core.domain.notification.ProviderSpec
import net.kirkanos.uptimekuma.core.ui.component.EmptyState
import net.kirkanos.uptimekuma.core.ui.theme.KumaTheme
import net.kirkanos.uptimekuma.feature.notifications.R
import net.kirkanos.uptimekuma.feature.notifications.icon
import net.kirkanos.uptimekuma.feature.notifications.labelRes

/** Grouping and search of the provider picker (pure, unit-tested). */
internal object ProviderCatalog {
    /** Display order of the categories. */
    val order: List<ProviderCategory> =
        listOf(
            ProviderCategory.UNIVERSAL,
            ProviderCategory.CHAT,
            ProviderCategory.PUSH,
            ProviderCategory.SMS,
            ProviderCategory.EMAIL,
            ProviderCategory.INCIDENT,
            ProviderCategory.HOME_AUTOMATION,
            ProviderCategory.REGIONAL,
            ProviderCategory.OTHER,
        )

    /**
     * Without [query]: every category with its providers (a provider in two categories appears twice, like in
     * the web UI). With a query: one flat group (`null` category) of providers whose label or id matches.
     */
    fun groups(
        spec: NotificationProviderSpec,
        query: String,
    ): List<Pair<ProviderCategory?, List<ProviderSpec>>> {
        val q = query.trim()
        if (q.isEmpty()) {
            return order.mapNotNull { category ->
                spec.providersIn(category).takeIf { it.isNotEmpty() }?.let {
                    category to
                        it
                }
            }
        }
        val hits =
            spec.providers.filter {
                it.label.contains(q, ignoreCase = true) ||
                    it.id.contains(q, ignoreCase = true)
            }
        return if (hits.isEmpty()) emptyList() else listOf(null to hits)
    }
}

@Composable
internal fun ProviderPickerEntry(
    onBack: () -> Unit,
    onPick: (String) -> Unit,
) {
    ProviderPickerScreen(spec = remember { NotificationProviderSpec.bundled }, onBack = onBack, onPick = onPick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderPickerScreen(
    spec: NotificationProviderSpec,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val groups = remember(spec, query) { ProviderCatalog.groups(spec, query) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notif_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.notif_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.notif_picker_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon =
                    if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.notif_picker_clear))
                            }
                        }
                    } else {
                        null
                    },
                supportingText = {
                    Text(
                        pluralStringResource(R.plurals.notif_picker_count, spec.providers.size, spec.providers.size),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (groups.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.notif_picker_no_results, query.trim()),
                    icon = Icons.Outlined.Search,
                )
            } else {
                ProviderList(groups, onPick)
            }
        }
    }
}

@Composable
private fun ProviderList(
    groups: List<Pair<ProviderCategory?, List<ProviderSpec>>>,
    onPick: (String) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        groups.forEach { (category, providers) ->
            if (category != null) {
                item(key = "header-${category.key}") {
                    Text(
                        stringResource(category.labelRes()),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                                .semantics { heading() },
                    )
                }
            }
            items(providers, key = { "${category?.key}-${it.id}" }) { provider ->
                ProviderRow(provider, onPick)
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: ProviderSpec,
    onPick: (String) -> Unit,
) {
    val enabled = provider.configurable
    ListItem(
        headlineContent = { Text(provider.label) },
        supportingContent =
            if (enabled) {
                null
            } else {
                { Text(stringResource(R.string.notif_picker_unsupported)) }
            },
        leadingContent = { Icon(provider.icon, contentDescription = null) },
        modifier = Modifier.clickable(enabled = enabled) { onPick(provider.id) },
    )
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun ProviderPickerPreview() {
    KumaTheme(dynamicColor = false) { ProviderPickerScreen(NotificationProviderSpec.bundled, {}, {}) }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProviderPickerSearchPreview() {
    KumaTheme(darkTheme = true, dynamicColor = false) {
        ProviderPickerScreen(NotificationProviderSpec.bundled, {}, {}, initialQuery = "push")
    }
}
