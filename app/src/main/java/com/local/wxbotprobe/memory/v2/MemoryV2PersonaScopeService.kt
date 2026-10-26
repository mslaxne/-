package com.local.wxbotprobe.memory.v2

import android.content.Context
import com.local.wxbotprobe.bot.PersonaSelection
import com.local.wxbotprobe.bot.PersonaSelectionService

data class MemoryV2PersonaScopePreview(
    val contactId: String,
    val personaId: String,
    val personaName: String,
    val scopeId: String?,
    val activeMemoryCount: Int,
    val scopeExists: Boolean,
)

data class MemoryV2PersonaSwitchResult(
    val selection: PersonaSelection,
    val scope: TrustedScopeContext,
    val activeMemoryCount: Int,
)

class MemoryV2PersonaScopeService(
    private val context: Context,
    database: MemoryV2Database = MemoryV2Database(context),
) {
    private val selections = PersonaSelectionService(context)
    private val resolver = MemoryV2ScopeResolver(context)
    private val repository = MemoryV2Repository(context, database)

    fun preview(
        contactId: String,
        personaId: String,
    ): MemoryV2PersonaScopePreview {
        val preset = selections.presetForBinding(personaId)
        val resolved = resolver.resolve(
            contactId = contactId,
            personaId = preset.id,
            persona = preset.persona,
        )
        val scope = repository.scopeForKey(resolved.key)
        return MemoryV2PersonaScopePreview(
            contactId = contactId,
            personaId = preset.id,
            personaName = preset.name,
            scopeId = scope?.scopeId,
            activeMemoryCount = scope?.let {
                repository.health(it.scopeId).activeMemories
            } ?: 0,
            scopeExists = scope != null,
        )
    }

    fun switch(
        contactId: String,
        personaId: String,
        displayName: String,
    ): MemoryV2PersonaSwitchResult {
        val preset = selections.presetForBinding(personaId)
        val resolved = resolver.resolve(
            contactId = contactId,
            personaId = preset.id,
            persona = preset.persona,
        )
        val scope = repository.ensureScope(
            resolved = resolved,
            contactDisplayName = displayName,
        )
        val selection = selections.bind(contactId, preset.id)
        return MemoryV2PersonaSwitchResult(
            selection = selection,
            scope = scope,
            activeMemoryCount = repository.health(scope.scopeId).activeMemories,
        )
    }

    fun followGlobalDefault(
        contactId: String,
        displayName: String,
    ): MemoryV2PersonaSwitchResult {
        val default = selections.globalDefault(contactId)
        val preset = selections.presetForBinding(default.personaId)
        val resolved = resolver.resolve(
            contactId = contactId,
            personaId = preset.id,
            persona = preset.persona,
        )
        val scope = repository.ensureScope(
            resolved = resolved,
            contactDisplayName = displayName,
        )
        val selection = selections.clearBinding(contactId)
        return MemoryV2PersonaSwitchResult(
            selection = selection,
            scope = scope,
            activeMemoryCount = repository.health(scope.scopeId).activeMemories,
        )
    }

    fun ensureUnboundScope(
        contactId: String,
        personaId: String,
        displayName: String,
    ): TrustedScopeContext {
        val preset = selections.presetForBinding(personaId)
        val resolved = resolver.resolve(
            contactId = contactId,
            personaId = preset.id,
            persona = preset.persona,
        )
        return repository.ensureScope(
            resolved = resolved,
            contactDisplayName = displayName,
        )
    }
}
