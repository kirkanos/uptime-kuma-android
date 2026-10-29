package net.kirkanos.uptimekuma.feature.statuspage

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import net.kirkanos.uptimekuma.feature.statuspage.detail.StatusPageDetailEntry
import net.kirkanos.uptimekuma.feature.statuspage.edit.StatusPageEditEntry
import net.kirkanos.uptimekuma.feature.statuspage.list.StatusPageListEntry
import net.kirkanos.uptimekuma.feature.statuspage.publicview.PublicStatusPageEntry

/** Public entry route of the feature: the list of status pages. */
@Serializable
data object StatusPageListRoute

/** Rendered status page of the logged-in server with admin actions. */
@Serializable
data class StatusPageDetailRoute(
    val slug: String,
) {
    companion object {
        /** SavedStateHandle key; must equal the property name. */
        const val ARG_SLUG = "slug"
    }
}

/** Settings, groups and monitors of a status page. */
@Serializable
data class StatusPageEditRoute(
    val slug: String,
) {
    companion object {
        const val ARG_SLUG = "slug"
    }
}

/** Read-only view of any public status page link (no login). */
@Serializable
data class PublicStatusPageRoute(
    val url: String,
) {
    companion object {
        const val ARG_URL = "url"
    }
}

/** Status page screens: list, detail, edit and the public viewer. Start at [StatusPageListRoute]. */
fun NavGraphBuilder.statusPagesGraph(navController: NavController) {
    composable<StatusPageListRoute> {
        StatusPageListEntry(
            onBack = { navController.popBackStack() },
            onOpen = { slug -> navController.navigate(StatusPageDetailRoute(slug)) },
            onCreated = { slug -> navController.navigate(StatusPageEditRoute(slug)) },
            onOpenPublic = { url -> navController.navigate(PublicStatusPageRoute(url)) },
        )
    }
    composable<StatusPageDetailRoute> {
        StatusPageDetailEntry(
            onBack = { navController.popBackStack() },
            onEdit = { slug -> navController.navigate(StatusPageEditRoute(slug)) },
        )
    }
    composable<StatusPageEditRoute> {
        StatusPageEditEntry(
            onBack = { navController.popBackStack() },
            onSaved = { slug ->
                // The slug may have changed: replace edit (and the old detail) by the saved page.
                navController.navigate(StatusPageDetailRoute(slug)) {
                    popUpTo<StatusPageListRoute> { inclusive = false }
                }
            },
        )
    }
    composable<PublicStatusPageRoute> {
        PublicStatusPageEntry(onBack = { navController.popBackStack() })
    }
}
