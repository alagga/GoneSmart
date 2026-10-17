package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProfileControlPolicyTest {

    @Test
    fun completeCardsUseTheirLeadingEnabledSwitchAsMaster() {
        val grouped = listOf(GoneSmartAudioProfiles.equalizerGroup) +
            GoneSmartAudioProfiles.effectGroups.filter { it.fields.size > 1 }

        grouped.forEach { group ->
            val master = AudioProfileControlPolicy.masterEnabledFieldId(group)
            assertEquals(group.fields.first().id, master)
            assertTrue(AudioProfileControlPolicy.isFieldInteractive(group, master!!, true, false))
            group.fields.drop(1).forEach { field ->
                assertFalse(AudioProfileControlPolicy.isFieldInteractive(group, field.id, true, false))
                assertTrue(AudioProfileControlPolicy.isFieldInteractive(group, field.id, true, true))
            }
        }
    }

    @Test
    fun standaloneBooleanCardsHaveNoDependentControls() {
        val standalone = GoneSmartAudioProfiles.effectGroups.filter { it.fields.size == 1 }
        standalone.forEach { group ->
            assertNull(AudioProfileControlPolicy.masterEnabledFieldId(group))
            assertTrue(AudioProfileControlPolicy.isFieldInteractive(group, group.fields.first().id, true, false))
            assertFalse(AudioProfileControlPolicy.isFieldInteractive(group, group.fields.first().id, false, true))
        }
    }
}
