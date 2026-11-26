package app.morphe.manager.ui.screen.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.morphe.manager.domain.catalog.*
import app.morphe.manager.domain.repository.PatchBundleRepository.RemoteUrlCheck
import app.morphe.manager.domain.repository.PatchBundleRepository.RemoteSourceRejection
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException

@Composable
internal fun SourceCatalogPanel(onCheckUrl: (String) -> RemoteUrlCheck, onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    var catalog by remember { mutableStateOf<PatchSourceCatalog?>(null) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val addedUrls = remember { mutableStateListOf<String>() }
    LaunchedEffect(reload) {
        loading = true
        error = false
        try { catalog = PatchSourceCatalogRepository.load(forceRefresh = reload > 0) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
        finally { loading = false }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search apps, sources, patches or authors") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        when {
            loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Could not load source catalog")
                TextButton(onClick = { reload++ }) { Text("Retry") }
            }
            catalog != null -> {
                val current = catalog!!
                val groups = remember(current, search) { PatchSourceCatalogRepository.filterAppGroups(current, search) }
                if (groups.isEmpty()) Text("No results found")
                else LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.forEach { group ->
                        val key = group.packageName ?: "__unknown__"
                        val isExpanded = expanded[key] ?: search.isNotBlank()
                        item(key = "app:$key") {
                            Row(modifier = Modifier.fillMaxWidth().clickable { expanded[key] = !isExpanded }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (isExpanded) "▾" else "▸", modifier = Modifier.padding(end = 10.dp))
                                if (!group.iconUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = group.iconUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                                    )
                                    Spacer(Modifier.width(10.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(group.name.ifBlank { "Sources without app information" }, style = MaterialTheme.typography.titleSmall)
                                    group.packageName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                                Text("${group.bundles.size} sources", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (isExpanded) items(group.bundles, key = { "$key:${it.source}:${it.repo}" }) { bundle ->
                            val url = bundle.url
                            val check = onCheckUrl(url)
                            val added = url in addedUrls || (check is RemoteUrlCheck.Rejected && check.reason == RemoteSourceRejection.Duplicate)
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(model = bundle.avatarUrl, contentDescription = null, modifier = Modifier.size(38.dp).clip(CircleShape))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(bundle.name.ifBlank { bundle.repo }, style = MaterialTheme.typography.titleSmall)
                                        val repoLabel = if (!bundle.author.isNullOrBlank()) "${bundle.repo} · ${bundle.author}" else bundle.repo
                                        Text(repoLabel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("★ ${bundle.stars} · ${bundle.patchCount} patches", style = MaterialTheme.typography.labelSmall)
                                        Text(bundle.targetApps.joinToString(", ") { current.store[it]?.name?.takeIf(String::isNotBlank) ?: it }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        bundle.repoDescription?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                    }
                                    TextButton(
                                        onClick = {
                                            addedUrls.add(url)
                                            onAdd(url)
                                        },
                                        enabled = !added && check is RemoteUrlCheck.Accepted
                                    ) { Text(if (added) "Added" else "Add") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
