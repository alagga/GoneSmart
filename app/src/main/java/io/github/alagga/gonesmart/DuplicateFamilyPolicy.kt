package io.github.alagga.gonesmart

enum class DuplicateConflictReason {
    VERSION,
    REMIX
}

data class DuplicateFamilyState(
    val versionFamilyKeys: MutableSet<String> = mutableSetOf(),
    val songIdentityKeys: MutableSet<String> = mutableSetOf(),
    val remixSongIdentityKeys: MutableSet<String> = mutableSetOf()
)

object DuplicateFamilyPolicy {

    fun conflictReason(
        candidate: TrackDuplicateIdentity,
        state: DuplicateFamilyState,
        preventBaseVersionDuplicates: Boolean,
        preventRemixDuplicates: Boolean
    ): DuplicateConflictReason? {

        if (
            preventBaseVersionDuplicates &&
            candidate.versionFamilyKeys.any {
                it in state.versionFamilyKeys
            }
        ) {

            return DuplicateConflictReason.VERSION
        }

        if (
            preventRemixDuplicates
        ) {

            val remixConflict =
                if (
                    candidate.isRemix
                ) {

                    candidate.songIdentityKeys.any {
                        it in state.songIdentityKeys
                    }

                } else {

                    candidate.songIdentityKeys.any {
                        it in state.remixSongIdentityKeys
                    }
                }

            if (
                remixConflict
            ) {

                return DuplicateConflictReason.REMIX
            }
        }

        return null
    }

    fun add(
        identity: TrackDuplicateIdentity,
        state: DuplicateFamilyState
    ) {

        state.versionFamilyKeys +=
            identity.versionFamilyKeys

        state.songIdentityKeys +=
            identity.songIdentityKeys

        if (
            identity.isRemix
        ) {

            state.remixSongIdentityKeys +=
                identity.songIdentityKeys
        }
    }
}
