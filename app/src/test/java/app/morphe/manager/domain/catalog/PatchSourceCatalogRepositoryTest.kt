package app.morphe.manager.domain.catalog

import kotlin.test.*

class PatchSourceCatalogRepositoryTest {
    private val shared = CatalogBundle(repo = "owner/shared", name = "Shared", stars = 50,
        targetApps = listOf("com.instagram", "com.facebook", "com.instagram"),
        patches = listOf(CatalogPatch("Remove ads")))
    private val catalog = PatchSourceCatalog(
        bundles = listOf(
            shared,
            CatalogBundle(repo = "other/in",
                targetApps = listOf("com.instagram"), stars = 3),
            CatalogBundle(repo = "other/duplicate", targetApps = listOf("org.instagram")),
            CatalogBundle(repo = "unknown/missing"),
            CatalogBundle(repo = "unknown/empty", targetApps = listOf("", "  "))
        ),
        store = mapOf("com.instagram" to CatalogStoreApp("Instagram"),
            "org.instagram" to CatalogStoreApp("Instagram"),
            "com.facebook" to CatalogStoreApp("Facebook"))
    )

    @Test fun `multi app source appears once per package and groups are ordered by count`() {
        val groups = PatchSourceCatalogRepository.filterAppGroups(catalog, "")
        assertEquals("com.instagram", groups.first().packageName)
        assertEquals(2, groups.first().bundles.size)
        assertEquals("owner/shared", groups.first().bundles.first().repo)
        assertEquals(null, groups.last().packageName)
        assertEquals(2, groups.last().bundles.size)
    }

    @Test fun `app keywords restrict groups and patch keywords restrict sources`() {
        val groups = PatchSourceCatalogRepository.filterAppGroups(catalog, "  INSTAGRAM   ads  ")
        assertEquals(1, groups.size)
        assertEquals("com.instagram", groups.single().packageName)
        assertEquals(listOf(shared), groups.single().bundles)
        assertEquals(2, PatchSourceCatalogRepository.filterAppGroups(catalog, "remove ads").size)
    }

    @Test fun `same app name different packages stay separate`() {
        val groups = PatchSourceCatalogRepository.filterAppGroups(catalog, "Instagram")
        assertEquals(setOf("com.instagram", "org.instagram"), groups.mapNotNull { it.packageName }.toSet())
    }

    @Test fun `missing store metadata falls back to package`() {
        val changed = catalog.copy(bundles = catalog.bundles + CatalogBundle(repo = "other/unlisted", targetApps = listOf("test.missing")))
        assertEquals("test.missing", PatchSourceCatalogRepository.filterAppGroups(changed, "test.missing").single().name)
    }

    @Test fun `repository urls preserve provider`() {
        assertEquals("https://github.com/owner/shared", shared.url)
        assertEquals("https://gitlab.com/group/repo", CatalogBundle(source = "gitlab", repo = "group/repo").url)
    }

    @Test fun `author search matches across apps and preserves store iconUrl`() {
        val withAuthor = PatchSourceCatalog(
            bundles = listOf(
                CatalogBundle(repo = "anddea/revanced-patches", name = "Anddea", author = "anddea", targetApps = listOf("com.google.android.youtube")),
                CatalogBundle(repo = "other/patches", name = "Other", author = "someone", targetApps = listOf("com.anddea.app"))
            ),
            store = mapOf(
                "com.google.android.youtube" to CatalogStoreApp("YouTube", iconUrl = "https://example.com/yt.png"),
                "com.anddea.app" to CatalogStoreApp("Some App")
            )
        )
        val groups = PatchSourceCatalogRepository.filterAppGroups(withAuthor, "anddea")
        val youtubeGroup = groups.firstOrNull { it.packageName == "com.google.android.youtube" }
        assertNotNull(youtubeGroup)
        assertEquals("https://example.com/yt.png", youtubeGroup.iconUrl)
    }
}
