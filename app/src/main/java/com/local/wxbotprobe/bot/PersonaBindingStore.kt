package com.local.wxbotprobe.bot

import android.content.Context
import com.local.wxbotprobe.memory.v2.MemoryV2IdentityStore

data class PersonaContactBinding(
    val contactId: String,
    val personaId: String,
)

data class PersonaSelection(
    val contactId: String,
    val personaId: String,
    val name: String,
    val persona: PersonaData,
    val preset: PersonaPreset?,
    val isDefault: Boolean,
    val fromExplicitBinding: Boolean,
)

object PersonaSelectionPolicy {
    fun choosePersonaId(
        persistedPersonaId: String?,
        defaultPersonaId: String,
        availablePersonaIds: Set<String>,
    ): String {
        require(defaultPersonaId.isNotBlank()) {
            "defaultPersonaId is required"
        }
        val persisted = persistedPersonaId?.takeIf {
            it.isNotBlank() && it in availablePersonaIds
        }
        return persisted ?: defaultPersonaId
    }

    fun preferenceKey(contactId: String): String {
        require(contactId.isNotBlank()) { "contactId is required" }
        return PREFIX + contactId
    }

    const val PREFIX = "persona-binding:"
}

class PersonaBindingStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun personaIdFor(contactId: String): String? {
        return preferences.getString(
            PersonaSelectionPolicy.preferenceKey(contactId),
            null,
        )?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun bind(contactId: String, personaId: String) {
        val key = PersonaSelectionPolicy.preferenceKey(contactId)
        require(personaId.isNotBlank()) { "personaId is required" }
        val committed = preferences.edit()
            .putInt(KEY_VERSION, FORMAT_VERSION)
            .putString(key, personaId)
            .commit()
        check(committed) { "unable to persist persona binding" }
    }

    @Synchronized
    fun clear(contactId: String) {
        val key = PersonaSelectionPolicy.preferenceKey(contactId)
        if (preferences.contains(key)) {
            val committed = preferences.edit().remove(key).commit()
            check(committed) { "unable to clear persona binding" }
        }
    }

    @Synchronized
    fun bindings(): Map<String, String> {
        return preferences.all.entries.mapNotNull { (key, value) ->
            val personaId = value as? String
            if (
                key.startsWith(PersonaSelectionPolicy.PREFIX) &&
                !personaId.isNullOrBlank()
            ) {
                key.removePrefix(PersonaSelectionPolicy.PREFIX) to personaId
            } else {
                null
            }
        }.toMap()
    }

    @Synchronized
    fun clearAll() {
        val committed = preferences.edit().clear().commit()
        check(committed) { "unable to clear persona bindings" }
    }

    companion object {
        private const val PREFS = "memory_v2_persona_bindings"
        private const val KEY_VERSION = "version"
        private const val FORMAT_VERSION = 1
    }
}

class PersonaSelectionService(
    private val context: Context,
    private val repository: PersonaPresetRepository =
        PersonaPresetRepository.forContext(context),
    private val bindings: PersonaBindingStore = PersonaBindingStore(context),
) {
    fun selectedForContact(contactId: String): PersonaSelection {
        require(contactId.isNotBlank()) { "contactId is required" }
        return runCatching {
            val default = repository.ensureMigrated().preset
            val persisted = bindings.personaIdFor(contactId)
            val active = repository.list().associateBy { it.id }
            val selectedId = PersonaSelectionPolicy.choosePersonaId(
                persistedPersonaId = persisted,
                defaultPersonaId = default.id,
                availablePersonaIds = active.keys,
            )
            if (persisted != null && persisted != selectedId) {
                bindings.clear(contactId)
            }
            val preset = active[selectedId] ?: default
            PersonaSelection(
                contactId = contactId,
                personaId = preset.id,
                name = preset.name,
                persona = preset.persona,
                preset = preset,
                isDefault = preset.isDefault,
                fromExplicitBinding = persisted == preset.id,
            )
        }.getOrElse {
            legacySelection(contactId)
        }
    }

    fun globalDefault(contactId: String): PersonaSelection {
        require(contactId.isNotBlank()) { "contactId is required" }
        val preset = repository.ensureMigrated().preset
        return PersonaSelection(
            contactId = contactId,
            personaId = preset.id,
            name = preset.name,
            persona = preset.persona,
            preset = preset,
            isDefault = true,
            fromExplicitBinding = false,
        )
    }

    fun setGlobalDefault(personaId: String): PersonaPreset {
        return repository.setDefault(personaId)
    }

    fun clearBinding(contactId: String): PersonaSelection {
        require(contactId.isNotBlank()) { "contactId is required" }
        bindings.clear(contactId)
        return globalDefault(contactId)
    }

    fun presetForBinding(personaId: String): PersonaPreset {
        val preset = repository.get(personaId)
        if (preset.archived) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.PRESET_ARCHIVED,
            )
        }
        return preset
    }

    fun bind(contactId: String, personaId: String): PersonaSelection {
        val preset = presetForBinding(personaId)
        bindings.bind(contactId, preset.id)
        return PersonaSelection(
            contactId = contactId,
            personaId = preset.id,
            name = preset.name,
            persona = preset.persona,
            preset = preset,
            isDefault = preset.isDefault,
            fromExplicitBinding = true,
        )
    }

    fun activePresets(): List<PersonaPreset> = repository.list()

    fun clearAll() {
        bindings.clearAll()
    }

    private fun legacySelection(contactId: String): PersonaSelection {
        val identity = MemoryV2IdentityStore(context)
        return PersonaSelection(
            contactId = contactId,
            personaId = identity.personaId,
            name = PersonaPresetRepository.DEFAULT_NAME,
            persona = PersonaProfileStore(context).load(),
            preset = null,
            isDefault = true,
            fromExplicitBinding = false,
        )
    }
}
