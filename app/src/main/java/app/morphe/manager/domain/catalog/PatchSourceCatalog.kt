package app.morphe.manager.domain.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

@Serializable
data class PatchSourceCatalog(
    val bundles: List<CatalogBundle> = emptyList(),
    val store: Map<String, CatalogStoreApp> = emptyMap()
)

@Serializable
data class CatalogBundle(
    val source: String = "github",
    val repo: String,
    val name: String = "",
    val author: String? = null,
    val repoDescription: String? = null,
    val targetApps: List<String> = emptyList(),
    val patchCount: Int = 0,
    val stars: Int = 0,
    val avatarUrl: String? = null,
    val patches: List<CatalogPatch> = emptyList()
) {
    val url: String get() = when (source.lowercase(Locale.ROOT)) {
        "gitlab" -> "https://gitlab.com/$repo"
        else -> "https://github.com/$repo"
    }
}

@Serializable
data class CatalogPatch(val name: String = "")

@Serializable
data class CatalogStoreApp(val name: String = "", val iconUrl: String? = null)

data class CatalogAppGroup(
    val packageName: String?,
    val name: String,
    val bundles: List<CatalogBundle>,
    val iconUrl: String? = null
)

/** In-memory cache is shared across panel openings, with a retry that bypasses the cache. */
object PatchSourceCatalogRepository {
    private const val CATALOG_URL = "https://morphe-patches.software/data/catalog.json"
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var cached: PatchSourceCatalog? = null

    suspend fun load(forceRefresh: Boolean = false): PatchSourceCatalog = mutex.withLock {
        if (!forceRefresh) cached?.let { return@withLock it }
        val result = withContext(Dispatchers.IO) {
            val connection = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "MorpheManager/Android")
            }
            try {
                connection.inputStream.bufferedReader().use { json.decodeFromString<PatchSourceCatalog>(it.readText()) }
            } finally {
                connection.disconnect()
            }
        }
        cached = result
        result
    }

    fun filterAppGroups(catalog: PatchSourceCatalog, query: String): List<CatalogAppGroup> {
        val grouped = linkedMapOf<String?, MutableList<CatalogBundle>>()
        catalog.bundles.forEach { bundle ->
            val packages = bundle.targetApps.filter { it.isNotBlank() }.distinct()
            if (packages.isEmpty()) grouped.getOrPut(null) { mutableListOf() }.add(bundle)
            else packages.forEach { grouped.getOrPut(it) { mutableListOf() }.add(bundle) }
        }
        val groups = grouped.map { (pkg, bundles) ->
            val storeApp = pkg?.let { catalog.store[it] }
            CatalogAppGroup(
                packageName = pkg,
                name = storeApp?.name?.takeIf { it.isNotBlank() } ?: pkg.orEmpty(),
                bundles = bundles.distinctBy { it.source to it.repo },
                iconUrl = storeApp?.iconUrl?.takeIf { it.isNotBlank() }
            )
        }
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        fun CatalogAppGroup.matchesApp(word: String) = packageName != null &&
            (name.contains(word, true) || packageName.contains(word, true))
        fun matchesAuthor(word: String) = catalog.bundles.any { bundle ->
            bundle.author?.contains(word, true) == true ||
                bundle.repo.substringBefore('/').contains(word, true)
        }
        val appWords = words.filter { word -> !matchesAuthor(word) && groups.any { it.matchesApp(word) } }
        val sourceWords = words.filterNot { it in appWords }
        val trimmed = query.trim().lowercase(Locale.ROOT)
        return groups.mapNotNull { group ->
            if (!appWords.all { group.matchesApp(it) }) return@mapNotNull null
            val bundles = group.bundles.filter { bundle ->
                val owner = bundle.repo.substringBefore('/')
                val searchable = listOfNotNull(bundle.name, bundle.author, owner, bundle.repo,
                    bundle.repoDescription).plus(bundle.patches.map { it.name })
                    .plus(bundle.targetApps.flatMap { pkg -> listOfNotNull(pkg, catalog.store[pkg]?.name) })
                sourceWords.all { word -> searchable.any { it.contains(word, true) } }
            }.sortedWith(
                compareByDescending<CatalogBundle> { bundle ->
                    var score = bundle.stars
                    if (trimmed.isNotEmpty()) {
                        val name = bundle.name.lowercase(Locale.ROOT)
                        val repo = bundle.repo.lowercase(Locale.ROOT)
                        val author = (bundle.author ?: "").lowercase(Locale.ROOT)
                        val owner = repo.substringBefore('/')
                        if (name == trimmed) score += 10000
                        else if (name.startsWith(trimmed)) score += 5000
                        else if (author == trimmed || owner == trimmed) score += 4000
                        else if (author.startsWith(trimmed) || owner.startsWith(trimmed)) score += 3000
                        else if (repo.contains(trimmed)) score += 2000
                    }
                    score
                }.thenBy { it.repo }
            )
            group.copy(bundles = bundles).takeIf { bundles.isNotEmpty() }
        }.sortedWith(compareBy<CatalogAppGroup> { it.packageName == null }
            .thenByDescending { it.bundles.size }.thenBy { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.packageName })
    }
}
