from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, got {count}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


MODULE = "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt"
MAIN = "app/src/main/java/io/github/alagga/gonesmart/MainActivity.kt"
PLAYLIST = "app/src/main/java/io/github/alagga/gonesmart/LocalPlaylistAffinityProvider.kt"

replace_once(
    MODULE,
    '''    /*
     * One provider pipeline at a time is intentional. Network
     * requests and full recommendation aggregation are the
     * expensive part; serializing them avoids duplicate work and
     * keeps resource usage predictable.
     */
    private val pipelineExecutor =
        Executors.newSingleThreadExecutor()

    private val startupExecutor =
''',
    '''    /*
     * Keep one overall pool-fill pipeline per session so queue state and
     * recommendation aggregation stay deterministic. Independent network
     * providers are parallelized inside that pipeline; provider priority is a
     * ranking weight, never an artificial request-order delay.
     */
    private val pipelineExecutor =
        Executors.newSingleThreadExecutor()

    private val providerExecutor =
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "GoneSmart-Provider").apply { isDaemon = true }
        }

    private val startupExecutor =
'''
)

replace_once(
    MODULE,
    '''            val playlistAffinityBatch =
                localPlaylistAffinityProvider.collect(
                    autoDjInstance = autoDjInstance,
                    seeds = seeds,
                    library = library,
                    maxResultsPerSeed = (targetPoolSize * 3).coerceAtLeast(24)
                )

            val rawContributions =
                mutableListOf<RawRecommendationContribution>()

            rawContributions +=
                playlistAffinityBatch.signals.mapNotNull { signal ->
                    val artist = signal.libraryTrack.track.artist?.trim().orEmpty()
                    val title = signal.libraryTrack.track.title?.trim().orEmpty()
                    if (artist.isBlank() || title.isBlank()) {
                        null
                    } else {
                        RawRecommendationContribution(
                            provider = RecommendationProvider.LOCAL_PLAYLIST,
                            seed = signal.seed,
                            artist = artist,
                            title = title,
                            mbid = null,
                            providerSimilarity = signal.score,
                            seedWeight = seedWeight(signal.seed),
                            identityConfidence = 1.0
                        )
                    }
                }
''',
    '''            val providerPolicy =
                RecommendationProviderPolicy.from(options)

            Log.i(
                TAG,
                "SMART DJ PROVIDER POLICY | mode=${providerPolicy.mode} | " +
                    "enabled=${providerPolicy.enabledProviders.sortedBy { it.name }} | " +
                    "weights=LB:${formatScore(providerPolicy.weight(RecommendationProvider.LISTENBRAINZ))}," +
                    "LASTFM:${formatScore(providerPolicy.weight(RecommendationProvider.LASTFM))}," +
                    "PLAYLIST:${formatScore(providerPolicy.weight(RecommendationProvider.LOCAL_PLAYLIST))} | " +
                    "playlistSize=${providerPolicy.playlistSizeWeighting}"
            )

            val playlistAffinityBatch =
                if (providerPolicy.isEnabled(RecommendationProvider.LOCAL_PLAYLIST)) {
                    localPlaylistAffinityProvider.collect(
                        autoDjInstance = autoDjInstance,
                        seeds = seeds,
                        library = library,
                        playlistSizeWeighting = providerPolicy.playlistSizeWeighting,
                        maxResultsPerSeed = (targetPoolSize * 3).coerceAtLeast(24)
                    )
                } else {
                    PlaylistAffinityBatch(emptyList(), PlaylistAffinityMetrics())
                }

            val rawContributions =
                mutableListOf<RawRecommendationContribution>()

            rawContributions +=
                playlistAffinityBatch.signals.mapNotNull { signal ->
                    val artist = signal.libraryTrack.track.artist?.trim().orEmpty()
                    val title = signal.libraryTrack.track.title?.trim().orEmpty()
                    if (artist.isBlank() || title.isBlank()) {
                        null
                    } else {
                        RawRecommendationContribution(
                            provider = RecommendationProvider.LOCAL_PLAYLIST,
                            seed = signal.seed,
                            artist = artist,
                            title = title,
                            mbid = null,
                            providerSimilarity = signal.score,
                            seedWeight = seedWeight(signal.seed),
                            identityConfidence = 1.0,
                            providerWeight = providerPolicy.weight(RecommendationProvider.LOCAL_PLAYLIST)
                        )
                    }
                }
'''
)

# The two normal seed lookups share the same policy snapshot for the whole fill.
replace_once(
    MODULE,
    '''                    collectSeedContributions(
                        seedNumber = seedNumber,
                        seed = seed,
                        metadata = metadata,
                        library = library
                    )
''',
    '''                    collectSeedContributions(
                        seedNumber = seedNumber,
                        seed = seed,
                        metadata = metadata,
                        library = library,
                        providerPolicy = providerPolicy
                    )
'''
)
replace_once(
    MODULE,
    '''                                collectSeedContributions(
                                    seedNumber = seedNumber,
                                    seed = seed,
                                    metadata = fallbackMetadata,
                                    library = library
                                )
''',
    '''                                collectSeedContributions(
                                    seedNumber = seedNumber,
                                    seed = seed,
                                    metadata = fallbackMetadata,
                                    library = library,
                                    providerPolicy = providerPolicy
                                )
'''
)

# There are two broad-search entry points in the pipeline.
old_broad = '''                    collectBroadSeedContributions(
                        seeds = seeds,
                        library = library
                    )
'''
new_broad = '''                    collectBroadSeedContributions(
                        seeds = seeds,
                        library = library,
                        providerPolicy = providerPolicy
                    )
'''
target = ROOT / MODULE
text = target.read_text(encoding="utf-8")
if text.count(old_broad) != 2:
    raise SystemExit(f"Expected two broad-search calls, got {text.count(old_broad)}")
target.write_text(text.replace(old_broad, new_broad), encoding="utf-8")

replace_once(
    MODULE,
    '''    private fun collectSeedContributions(
        seedNumber: Int,
        seed: RecommendationSeed,
        metadata: NormalizedTrackMetadata,
        library: List<GmmpLibraryTrack>,
        broadSearch: Boolean = false
    ): List<RawRecommendationContribution> {
        if (networkStateReader.getState() != GoneSmartNetworkState.ONLINE) {
            return emptyList()
        }

        val result = mutableListOf<RawRecommendationContribution>()
        result += collectListenBrainzContributions(
            seedNumber = seedNumber,
            seed = seed,
            metadata = metadata,
            library = library,
            broadSearch = broadSearch
        )
        result += collectLastFmContributions(
            seedNumber = seedNumber,
            seed = seed,
            metadata = metadata,
            broadSearch = broadSearch
        )
        return result
    }

    private fun collectBroadSeedContributions(
        seeds: List<RecommendationSeed>,
        library: List<GmmpLibraryTrack>
    ): List<RawRecommendationContribution> {
        if (networkStateReader.getState() != GoneSmartNetworkState.ONLINE) {
            return emptyList()
        }

        val result = mutableListOf<RawRecommendationContribution>()
        seeds.forEachIndexed { seedIndex, seed ->
            val seedNumber = seedIndex + 1
            val resolvedInput = trackInputResolver.resolve(seed.track)
            val metadata = metadataNormalizer.normalize(resolvedInput.track)
            if (metadata.searchArtist.isBlank() || metadata.searchTitle.isBlank()) {
                return@forEachIndexed
            }

            var contributions = collectSeedContributions(
                seedNumber = seedNumber,
                seed = seed,
                metadata = metadata,
                library = library,
                broadSearch = true
            )

            if (contributions.isEmpty() && resolvedInput.source != TrackInputSource.TAGS) {
                val fallbackTrack = buildProviderFallbackTrack(resolvedInput.track)
                if (fallbackTrack != null) {
                    val fallbackMetadata = metadataNormalizer.normalize(fallbackTrack)
                    if (fallbackMetadata.searchArtist.isNotBlank() &&
                        fallbackMetadata.searchTitle.isNotBlank()
                    ) {
                        contributions = collectSeedContributions(
                            seedNumber = seedNumber,
                            seed = seed,
                            metadata = fallbackMetadata,
                            library = library,
                            broadSearch = true
                        )
                    }
                }
            }
            result += contributions
        }
        return result
    }
''',
    '''    private fun collectSeedContributions(
        seedNumber: Int,
        seed: RecommendationSeed,
        metadata: NormalizedTrackMetadata,
        library: List<GmmpLibraryTrack>,
        providerPolicy: RecommendationProviderPolicy,
        broadSearch: Boolean = false
    ): List<RawRecommendationContribution> {
        if (networkStateReader.getState() != GoneSmartNetworkState.ONLINE) {
            return emptyList()
        }

        val startedNs = System.nanoTime()
        val tasks = mutableListOf<Pair<RecommendationProvider, Future<List<RawRecommendationContribution>>>>()

        if (providerPolicy.isEnabled(RecommendationProvider.LISTENBRAINZ)) {
            tasks += RecommendationProvider.LISTENBRAINZ to
                providerExecutor.submit<List<RawRecommendationContribution>> {
                    collectListenBrainzContributions(
                        seedNumber = seedNumber,
                        seed = seed,
                        metadata = metadata,
                        library = library,
                        broadSearch = broadSearch
                    )
                }
        }
        if (providerPolicy.isEnabled(RecommendationProvider.LASTFM)) {
            tasks += RecommendationProvider.LASTFM to
                providerExecutor.submit<List<RawRecommendationContribution>> {
                    collectLastFmContributions(
                        seedNumber = seedNumber,
                        seed = seed,
                        metadata = metadata,
                        broadSearch = broadSearch
                    )
                }
        }

        if (tasks.isEmpty()) return emptyList()

        val result = mutableListOf<RawRecommendationContribution>()
        for ((provider, future) in tasks) {
            val contributions = try {
                future.get()
            } catch (interrupted: InterruptedException) {
                tasks.forEach { it.second.cancel(true) }
                Thread.currentThread().interrupt()
                return emptyList()
            } catch (throwable: Throwable) {
                Log.w(TAG, "$provider contribution task failed for seed $seedNumber", throwable)
                emptyList()
            }

            val providerWeight = providerPolicy.weight(provider)
            result += contributions.map { it.copy(providerWeight = providerWeight) }
        }

        Log.i(
            TAG,
            "SMART DJ PROVIDER ROUND | seed=$seedNumber | broad=$broadSearch | " +
                "enabled=${tasks.map { it.first }} | contributions=${result.size} | " +
                "elapsedMs=${(System.nanoTime() - startedNs) / 1_000_000L}"
        )
        return result
    }

    private fun collectBroadSeedContributions(
        seeds: List<RecommendationSeed>,
        library: List<GmmpLibraryTrack>,
        providerPolicy: RecommendationProviderPolicy
    ): List<RawRecommendationContribution> {
        if (networkStateReader.getState() != GoneSmartNetworkState.ONLINE) {
            return emptyList()
        }

        val result = mutableListOf<RawRecommendationContribution>()
        seeds.forEachIndexed { seedIndex, seed ->
            val seedNumber = seedIndex + 1
            val resolvedInput = trackInputResolver.resolve(seed.track)
            val metadata = metadataNormalizer.normalize(resolvedInput.track)
            if (metadata.searchArtist.isBlank() || metadata.searchTitle.isBlank()) {
                return@forEachIndexed
            }

            var contributions = collectSeedContributions(
                seedNumber = seedNumber,
                seed = seed,
                metadata = metadata,
                library = library,
                providerPolicy = providerPolicy,
                broadSearch = true
            )

            if (contributions.isEmpty() && resolvedInput.source != TrackInputSource.TAGS) {
                val fallbackTrack = buildProviderFallbackTrack(resolvedInput.track)
                if (fallbackTrack != null) {
                    val fallbackMetadata = metadataNormalizer.normalize(fallbackTrack)
                    if (fallbackMetadata.searchArtist.isNotBlank() &&
                        fallbackMetadata.searchTitle.isNotBlank()
                    ) {
                        contributions = collectSeedContributions(
                            seedNumber = seedNumber,
                            seed = seed,
                            metadata = fallbackMetadata,
                            library = library,
                            providerPolicy = providerPolicy,
                            broadSearch = true
                        )
                    }
                }
            }
            result += contributions
        }
        return result
    }
'''
)

replace_once(
    MODULE,
    '''            // Recording graph exhausted: use ListenBrainz's official
            // similar-artists dataset as a lower-confidence signal and expand
            // it only to tracks that are already in the local GMMP library.
            val fallbackEvaluation = evaluations.firstOrNull {
                it.match.artistMbids.isNotEmpty()
            }
            val artistMbid = fallbackEvaluation?.match?.artistMbids?.firstOrNull()
            if (fallbackEvaluation != null && !artistMbid.isNullOrBlank()) {
                val artists = try {
                    listenBrainzClient.getSimilarArtists(
                        artistMbid = artistMbid,
                        limit = if (broadSearch) 20 else 12
                    )
                } catch (_: Throwable) {
                    emptyList()
                }
                val expanded = localArtistSignalExpander.expand(
                    artists = artists,
                    library = library,
                    excludedTrackIds = setOf(seed.track.id),
                    maxArtists = if (broadSearch) 20 else 12,
                    maxTracksPerArtist = 3
                )
                if (expanded.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "LB ARTIST FALLBACK seed=$seedNumber | artists=${artists.size} | " +
                            "localTracks=${expanded.size}"
                    )
                    return expanded.mapNotNull { item ->
                        val artist = item.libraryTrack.track.artist?.trim().orEmpty()
                        val title = item.libraryTrack.track.title?.trim().orEmpty()
                        if (artist.isBlank() || title.isBlank()) null else
                            RawRecommendationContribution(
                                provider = RecommendationProvider.LISTENBRAINZ,
                                seed = seed,
                                artist = artist,
                                title = title,
                                mbid = null,
                                providerSimilarity = (
                                    item.providerScore * LISTENBRAINZ_ARTIST_FALLBACK_PENALTY
                                ).coerceIn(0.0, 1.0),
                                seedWeight = seedWeight(seed),
                                identityConfidence = fallbackEvaluation.totalScore
                                    .coerceIn(0.0, 1.0)
                            )
                    }
                }
            }
''',
    '''            // Recording graph exhausted: use ListenBrainz's official
            // similar-artists dataset as a lower-confidence signal and expand
            // it only to tracks that are already in the local GMMP library.
            // A sparse/new track may have no accepted recording identity even
            // though recording search still returned a reliable artist MBID.
            // Recover only that artist identity; never treat the rejected
            // recording row itself as a track recommendation.
            val fallbackEvaluation = evaluations.firstOrNull {
                it.match.artistMbids.isNotEmpty()
            }
            val acceptedArtistMbid =
                fallbackEvaluation?.match?.artistMbids?.firstOrNull()
            val recoveredArtistIdentity =
                if (acceptedArtistMbid.isNullOrBlank()) {
                    ListenBrainzArtistIdentityPolicy.select(
                        metadata = metadata,
                        matches = matches,
                        comparisonKey = metadataNormalizer::comparisonKey
                    )
                } else {
                    null
                }
            val artistMbid = acceptedArtistMbid ?: recoveredArtistIdentity?.artistMbid
            val fallbackConfidence =
                fallbackEvaluation?.totalScore?.coerceIn(0.0, 1.0)
                    ?: recoveredArtistIdentity?.confidence?.coerceIn(0.0, 1.0)
                    ?: 0.0

            if (recoveredArtistIdentity != null) {
                Log.i(
                    TAG,
                    "LB ARTIST IDENTITY FALLBACK seed=$seedNumber | " +
                        "artist=${recoveredArtistIdentity.artistName} | " +
                        "confidence=${formatScore(recoveredArtistIdentity.confidence)}"
                )
            }

            if (!artistMbid.isNullOrBlank() && fallbackConfidence > 0.0) {
                val artists = try {
                    listenBrainzClient.getSimilarArtists(
                        artistMbid = artistMbid,
                        limit = if (broadSearch) 20 else 12
                    )
                } catch (_: Throwable) {
                    emptyList()
                }
                val expanded = localArtistSignalExpander.expand(
                    artists = artists,
                    library = library,
                    excludedTrackIds = setOf(seed.track.id),
                    maxArtists = if (broadSearch) 20 else 12,
                    maxTracksPerArtist = 3
                )
                if (expanded.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "LB ARTIST FALLBACK seed=$seedNumber | artists=${artists.size} | " +
                            "localTracks=${expanded.size} | identityConfidence=${formatScore(fallbackConfidence)}"
                    )
                    return expanded.mapNotNull { item ->
                        val artist = item.libraryTrack.track.artist?.trim().orEmpty()
                        val title = item.libraryTrack.track.title?.trim().orEmpty()
                        if (artist.isBlank() || title.isBlank()) null else
                            RawRecommendationContribution(
                                provider = RecommendationProvider.LISTENBRAINZ,
                                seed = seed,
                                artist = artist,
                                title = title,
                                mbid = null,
                                providerSimilarity = (
                                    item.providerScore * LISTENBRAINZ_ARTIST_FALLBACK_PENALTY
                                ).coerceIn(0.0, 1.0),
                                seedWeight = seedWeight(seed),
                                identityConfidence = fallbackConfidence
                            )
                    }
                }
            }
'''
)

replace_once(
    PLAYLIST,
    '''    fun collect(
        autoDjInstance: Any,
        seeds: List<RecommendationSeed>,
        library: List<GmmpLibraryTrack>,
        maxResultsPerSeed: Int = DEFAULT_MAX_RESULTS_PER_SEED
    ): PlaylistAffinityBatch {
''',
    '''    fun collect(
        autoDjInstance: Any,
        seeds: List<RecommendationSeed>,
        library: List<GmmpLibraryTrack>,
        playlistSizeWeighting: PlaylistSizeWeighting = PlaylistSizeWeighting.BALANCED,
        maxResultsPerSeed: Int = DEFAULT_MAX_RESULTS_PER_SEED
    ): PlaylistAffinityBatch {
'''
)
replace_once(
    PLAYLIST,
    '''                    val score = PlaylistAffinityScoring.score(sizes)
''',
    '''                    val score = PlaylistAffinityScoring.score(sizes, playlistSizeWeighting)
'''
)
replace_once(
    PLAYLIST,
    '''                "seedHits=${metrics.seedsMatched}/${seeds.size} | signals=${metrics.signals} | " +
                "elapsedMs=${metrics.elapsedMs}"
''',
    '''                "seedHits=${metrics.seedsMatched}/${seeds.size} | signals=${metrics.signals} | " +
                "sizeWeighting=$playlistSizeWeighting | elapsedMs=${metrics.elapsedMs}"
'''
)

replace_once(
    MAIN,
    '''        container.addView(settingGroup(listOf(
            SettingSpec(
                key = GoneSmartSettingsKeys.KEY_ENABLED,
                glyph = "✦",
                title = "Enable Smart DJ",
                subtitle = "Let GoneSmart choose music when GMMP Auto-DJ needs more tracks.",
                accent = COLOR_ACCENT
            )
        )))

        container.addView(verticalGap(24))
        container.addView(sectionTitle("MATCHING"))
''',
    '''        container.addView(settingGroup(listOf(
            SettingSpec(
                key = GoneSmartSettingsKeys.KEY_ENABLED,
                glyph = "✦",
                title = "Enable Smart DJ",
                subtitle = "Let GoneSmart choose music when GMMP Auto-DJ needs more tracks.",
                accent = COLOR_ACCENT
            )
        )))

        val recommendationOptions = settingsRepository.read()
        container.addView(verticalGap(24))
        container.addView(sectionTitle("RECOMMENDATION SOURCES"))
        container.addView(recommendationModeCard(recommendationOptions))
        container.addView(verticalGap(10))
        if (recommendationOptions.recommendationMode == RecommendationMode.SMART) {
            container.addView(infoCard(
                title = "Smart weighting",
                body = "GoneSmart uses all stable sources automatically: Local Playlists ×1.00, ListenBrainz ×1.00 and Last.fm ×0.95, with balanced down-weighting for very large playlists. Network sources run in parallel; GoneSmart can refine these defaults in future updates."
            ))
        } else {
            container.addView(settingGroup(listOf(
                SettingSpec(GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_ENABLED, "P", "Local Playlists", "Use co-occurrence in your normal GMMP playlists as a personal recommendation signal.", COLOR_GREEN),
                SettingSpec(GoneSmartSettingsKeys.KEY_PROVIDER_LISTENBRAINZ_ENABLED, "L", "ListenBrainz", "Use recording similarity and a lower-confidence similar-artist fallback.", COLOR_ACCENT),
                SettingSpec(GoneSmartSettingsKeys.KEY_PROVIDER_LASTFM_ENABLED, "F", "Last.fm", "Use similar-track data and the bounded artist/top-track fallback.", COLOR_AMBER)
            )))
            container.addView(verticalGap(10))
            container.addView(providerWeightCard(
                title = "Local Playlist weight",
                subtitle = "How strongly personal playlist co-occurrence influences the final ranking.",
                key = GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_WEIGHT,
                value = recommendationOptions.localPlaylistProviderWeight
            ))
            container.addView(verticalGap(10))
            container.addView(providerWeightCard(
                title = "ListenBrainz weight",
                subtitle = "How strongly ListenBrainz evidence influences the final ranking.",
                key = GoneSmartSettingsKeys.KEY_PROVIDER_LISTENBRAINZ_WEIGHT,
                value = recommendationOptions.listenBrainzProviderWeight
            ))
            container.addView(verticalGap(10))
            container.addView(providerWeightCard(
                title = "Last.fm weight",
                subtitle = "How strongly Last.fm evidence influences the final ranking.",
                key = GoneSmartSettingsKeys.KEY_PROVIDER_LASTFM_WEIGHT,
                value = recommendationOptions.lastFmProviderWeight
            ))
            container.addView(verticalGap(10))
            container.addView(playlistSizeWeightingCard(recommendationOptions))
            container.addView(verticalGap(10))
            container.addView(infoCard(
                title = "Provider priority",
                body = "Weights control recommendation priority. Request order is intentionally not configurable because ListenBrainz and Last.fm run in parallel, avoiding unnecessary waiting for one service before the other can start."
            ))
        }

        container.addView(verticalGap(24))
        container.addView(sectionTitle("MATCHING"))
'''
)

replace_once(
    MAIN,
    '''        container.addView(infoCard(
            title = "Offline behavior",
            body = "A valid pool from the current queue can continue offline. A new queue never reuses an old pool; if GoneSmart has no usable cached track, GMMP Auto-DJ takes over and the player sparkle turns red."
        ))
''',
    '''        container.addView(infoCard(
            title = "Offline behavior",
            body = "Local Playlist recommendations can be generated without a network connection, and a valid pool from the current queue can also continue offline. Online providers are skipped while offline. A new queue never reuses an old pool; if no enabled local source can supply a suitable track, the configured GMMP Auto-DJ fallback can take over."
        ))
'''
)

replace_once(
    MAIN,
    '''    private fun companionTrackAutoDjLabel(): String = TrackMixPlan.COMPANION_LABEL
''',
    '''    private fun recommendationModeCard(options: GoneSmartOptions): View {
        val card = card().apply {
            setContentPadding(dp(20), dp(18), dp(20), dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener { showRecommendationModeDialog(options.recommendationMode) }
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            textView("Recommendation mode", 16f, COLOR_TEXT, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(textView(
            if (options.recommendationMode == RecommendationMode.SMART) "Smart" else "Custom",
            15f,
            COLOR_ACCENT,
            bold = true
        ))
        content.addView(row)
        content.addView(textView(
            if (options.recommendationMode == RecommendationMode.SMART)
                "Recommended. GoneSmart chooses stable sources and balanced weights automatically. Tap to customize."
            else
                "Choose providers, their ranking weights and how strongly very large playlists are down-weighted. Tap to return to Smart.",
            13f,
            COLOR_TEXT_SECONDARY
        ).apply { setPadding(0, dp(5), 0, 0) })
        card.addView(content)
        return card
    }

    private fun showRecommendationModeDialog(current: RecommendationMode) {
        val modes = arrayOf("Smart (recommended)", "Custom")
        val checked = if (current == RecommendationMode.SMART) 0 else 1
        AlertDialog.Builder(this)
            .setTitle("Recommendation mode")
            .setSingleChoiceItems(modes, checked) { dialog, which ->
                val selected = if (which == 0) RecommendationMode.SMART else RecommendationMode.CUSTOM
                settingsRepository.setString(
                    GoneSmartSettingsKeys.KEY_RECOMMENDATION_MODE,
                    selected.preferenceValue
                )
                dialog.dismiss()
                showTab(Tab.SMART)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun providerWeightCard(
        title: String,
        subtitle: String,
        key: String,
        value: Double
    ): View {
        val card = card().apply { setContentPadding(dp(20), dp(18), dp(20), dp(14)) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            textView(title, 16f, COLOR_TEXT, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val valueText = textView(providerWeightLabel(value), 15f, COLOR_ACCENT, bold = true)
        titleRow.addView(valueText)
        content.addView(titleRow)
        content.addView(textView(subtitle, 13f, COLOR_TEXT_SECONDARY).apply {
            setPadding(0, dp(5), 0, dp(8))
        })

        val slider = Slider(this).apply {
            valueFrom = RecommendationProviderPolicy.MIN_PROVIDER_WEIGHT.toFloat()
            valueTo = RecommendationProviderPolicy.MAX_PROVIDER_WEIGHT.toFloat()
            stepSize = 0.05f
            this.value = value.toFloat().coerceIn(valueFrom, valueTo)
            trackActiveTintList = ColorStateList.valueOf(COLOR_ACCENT)
            trackInactiveTintList = ColorStateList.valueOf(0xFF494650.toInt())
            thumbTintList = ColorStateList.valueOf(COLOR_ACCENT)
            haloTintList = ColorStateList.valueOf(withAlpha(COLOR_ACCENT, 0.20f))
            setLabelFormatter { providerWeightLabel(it.toDouble()) }
            addOnChangeListener { slider, sliderValue, fromUser ->
                valueText.text = providerWeightLabel(sliderValue.toDouble())
                if (fromUser && !slider.isPressed) {
                    settingsRepository.setFloat(key, sliderValue)
                }
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit
                override fun onStopTrackingTouch(slider: Slider) {
                    settingsRepository.setFloat(key, slider.value)
                }
            })
        }
        content.addView(slider)
        card.addView(content)
        return card
    }

    private fun providerWeightLabel(value: Double): String =
        String.format(java.util.Locale.US, "×%.2f", value)

    private fun playlistSizeWeightingCard(options: GoneSmartOptions): View {
        val label = when (options.playlistSizeWeighting) {
            PlaylistSizeWeighting.OFF -> "Off"
            PlaylistSizeWeighting.BALANCED -> "Balanced"
            PlaylistSizeWeighting.STRONG -> "Strong"
        }
        val card = card().apply {
            setContentPadding(dp(20), dp(18), dp(20), dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener { showPlaylistSizeWeightingDialog(options.playlistSizeWeighting) }
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            textView("Large-playlist weighting", 16f, COLOR_TEXT, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(textView(label, 15f, COLOR_ACCENT, bold = true))
        content.addView(row)
        content.addView(textView(
            "Off treats every playlist equally. Balanced favors focused playlists while keeping large lists useful. Strong reduces the influence of very large catch-all playlists further.",
            13f,
            COLOR_TEXT_SECONDARY
        ).apply { setPadding(0, dp(5), 0, 0) })
        card.addView(content)
        return card
    }

    private fun showPlaylistSizeWeightingDialog(current: PlaylistSizeWeighting) {
        val values = PlaylistSizeWeighting.entries
        val labels = arrayOf("Off", "Balanced (recommended)", "Strong")
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Large-playlist weighting")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = values[which]
                settingsRepository.setString(
                    GoneSmartSettingsKeys.KEY_PLAYLIST_SIZE_WEIGHTING,
                    selected.preferenceValue
                )
                dialog.dismiss()
                showTab(Tab.SMART)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun companionTrackAutoDjLabel(): String = TrackMixPlan.COMPANION_LABEL
'''
)

replace_once(
    MAIN,
    '''        setSwitch(GoneSmartSettingsKeys.KEY_TRACK_MIX, options.trackMixEnabled)
        setSwitch(GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED, options.preferHigherRatedMatches)
''',
    '''        setSwitch(GoneSmartSettingsKeys.KEY_TRACK_MIX, options.trackMixEnabled)
        setSwitch(GoneSmartSettingsKeys.KEY_PROVIDER_LISTENBRAINZ_ENABLED, options.listenBrainzProviderEnabled)
        setSwitch(GoneSmartSettingsKeys.KEY_PROVIDER_LASTFM_ENABLED, options.lastFmProviderEnabled)
        setSwitch(GoneSmartSettingsKeys.KEY_PROVIDER_LOCAL_PLAYLIST_ENABLED, options.localPlaylistProviderEnabled)
        setSwitch(GoneSmartSettingsKeys.KEY_PREFER_HIGHER_RATED, options.preferHigherRatedMatches)
'''
)

replace_once(
    MAIN,
    '''        container.addView(infoCard(
            title = "ListenBrainz + Last.fm",
            body = "ListenBrainz provides MusicBrainz-backed recording lookup and similar-recording data. Last.fm contributes similar-track data. GoneSmart sends only the seed metadata needed for those recommendation lookups; your full GMMP library stays local and is matched on-device. GoneSmart does not stream music from either service."
        ))
''',
    '''        container.addView(infoCard(
            title = "Local Playlists + ListenBrainz + Last.fm",
            body = "Smart mode combines personal co-occurrence from your normal local playlists with ListenBrainz and Last.fm. ListenBrainz and Last.fm run in parallel when online; your full GMMP library stays local and is matched on-device. Custom mode lets you disable sources or change their ranking weights. GoneSmart does not stream music from any provider."
        ))
'''
)

print("Provider settings patch applied successfully")
