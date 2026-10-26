package com.local.wxbotprobe.bot

import android.content.Context
import com.local.wxbotprobe.memory.v2.MemoryV2IdentityStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class PersonaPreset(
    val id: String,
    val name: String,
    val persona: PersonaData,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?,
    val revision: Int,
    val isDefault: Boolean,
) {
    val archived: Boolean
        get() = archivedAt != null
}

data class PersonaPresetUsage(
    val contactIds: Set<String> = emptySet(),
    val scopeCount: Int = 0,
    val memoryCount: Int = 0,
) {
    val contactCount: Int
        get() = contactIds.size

    val usedByContact: Boolean
        get() = contactIds.isNotEmpty()

    val hasMemoryScopeData: Boolean
        get() = scopeCount > 0 || memoryCount > 0
}

data class PersonaPresetMigration(
    val preset: PersonaPreset,
    val migratedLegacyFile: Boolean,
)

enum class PersonaPresetErrorCode {
    INVALID_DEFAULT_ID,
    INVALID_PRESET_ID,
    INVALID_NAME,
    PRESET_NOT_FOUND,
    DEFAULT_PRESET_MISSING,
    STALE_PRESET,
    STALE_NAME,
    PRESET_ARCHIVED,
    DEFAULT_PROTECTED,
    CONTACT_REFERENCE_PROTECTED,
    SCOPE_REFERENCE_PROTECTED,
    DELETE_REQUIRES_ARCHIVE,
    LEGACY_JSON_INVALID,
    REPOSITORY_JSON_INVALID,
    STORAGE_FAILURE,
}

class PersonaPresetException(
    val code: PersonaPresetErrorCode,
    detail: String = "",
) : IllegalStateException(
    if (detail.isBlank()) code.name else "${code.name}: $detail",
)

class PersonaPresetRepository(
    private val rootDirectory: File,
    private val defaultPersonaId: String,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val legacyFile = File(rootDirectory, LEGACY_FILE_NAME)
    private val repositoryFile = File(rootDirectory, REPOSITORY_FILE_NAME)

    init {
        require(defaultPersonaId.isNotBlank()) {
            "defaultPersonaId is required"
        }
    }

    fun repositoryExists(): Boolean = repositoryFile.isFile

    fun legacyExists(): Boolean = legacyFile.isFile

    @Synchronized
    fun ensureMigrated(): PersonaPresetMigration {
        if (repositoryFile.isFile) {
            val collection = readCollection()
            val anchor = collection.presets.firstOrNull {
                it.id == collection.systemDefaultPresetId
            }
            if (anchor == null || anchor.id != defaultPersonaId) {
                throw PersonaPresetException(
                    PersonaPresetErrorCode.INVALID_DEFAULT_ID,
                )
            }
            val default = collection.presets.firstOrNull {
                it.id == collection.defaultPresetId
            }
                ?: throw PersonaPresetException(
                    PersonaPresetErrorCode.DEFAULT_PRESET_MISSING,
                )
            if (default.archived || !default.isDefault) {
                throw PersonaPresetException(
                    PersonaPresetErrorCode.INVALID_DEFAULT_ID,
                )
            }
            if (collection.formatVersion != FORMAT_VERSION) {
                writeCollection(
                    collection.copy(formatVersion = FORMAT_VERSION),
                )
            }
            return PersonaPresetMigration(default, migratedLegacyFile = false)
        }

        val legacyProfile = readLegacyProfile()
        val now = nowSeconds()
        val default = PersonaPreset(
            id = defaultPersonaId,
            name = DEFAULT_NAME,
            persona = legacyProfile,
            createdAt = now,
            updatedAt = now,
            archivedAt = null,
            revision = 1,
            isDefault = true,
        )
        writeCollection(
            PersonaPresetCollection(
                formatVersion = FORMAT_VERSION,
                systemDefaultPresetId = defaultPersonaId,
                defaultPresetId = defaultPersonaId,
                presets = listOf(default),
            ),
        )
        return PersonaPresetMigration(default, migratedLegacyFile = true)
    }

    fun list(includeArchived: Boolean = false): List<PersonaPreset> {
        ensureMigrated()
        return readCollection().presets
            .filter { includeArchived || !it.archived }
            .sortedWith(
                compareByDescending<PersonaPreset> { it.isDefault }
                    .thenBy { it.archived }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.name.lowercase() },
            )
    }

    fun get(id: String): PersonaPreset {
        ensureMigrated()
        return readCollection().presets.firstOrNull { it.id == id }
            ?: throw PersonaPresetException(
                PersonaPresetErrorCode.PRESET_NOT_FOUND,
            )
    }

    fun create(
        name: String,
        persona: PersonaData,
    ): PersonaPreset {
        ensureMigrated()
        val collection = readCollection()
        val now = nowSeconds()
        val preset = PersonaPreset(
            id = newPresetId(collection.presets),
            name = cleanName(name),
            persona = PersonaProfileCodec.sanitise(persona),
            createdAt = now,
            updatedAt = now,
            archivedAt = null,
            revision = 1,
            isDefault = false,
        )
        writeCollection(
            collection.copy(presets = collection.presets + preset),
        )
        return preset
    }

    fun duplicate(
        sourceId: String,
        name: String? = null,
    ): PersonaPreset {
        val source = get(sourceId)
        return create(
            name = name?.takeIf { it.isNotBlank() }
                ?: duplicateName(source.name),
            persona = source.persona,
        )
    }

    fun update(
        id: String,
        name: String,
        persona: PersonaData,
        expectedRevision: Int,
    ): PersonaPreset {
        ensureMigrated()
        val collection = readCollection()
        val existing = collection.presets.firstOrNull { it.id == id }
            ?: throw PersonaPresetException(
                PersonaPresetErrorCode.PRESET_NOT_FOUND,
            )
        if (existing.archived) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.PRESET_ARCHIVED,
            )
        }
        if (existing.revision != expectedRevision) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.STALE_PRESET,
            )
        }
        val updated = existing.copy(
            name = cleanName(name),
            persona = PersonaProfileCodec.sanitise(persona),
            updatedAt = maxOf(nowSeconds(), existing.updatedAt + 1),
            revision = existing.revision + 1,
        )
        writeCollection(
            collection.copy(
                presets = collection.presets.map {
                    if (it.id == id) updated else it
                },
            ),
        )
        return updated
    }

    fun updateDefaultPersona(persona: PersonaData): PersonaPreset {
        val default = ensureMigrated().preset
        return update(
            id = default.id,
            name = default.name,
            persona = persona,
            expectedRevision = default.revision,
        )
    }

    fun setDefault(id: String): PersonaPreset {
        ensureMigrated()
        val collection = readCollection()
        val target = collection.presets.firstOrNull { it.id == id }
            ?: throw PersonaPresetException(
                PersonaPresetErrorCode.PRESET_NOT_FOUND,
            )
        if (target.archived) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.PRESET_ARCHIVED,
            )
        }
        if (collection.defaultPresetId == target.id) {
            return target
        }
        val updatedTarget = target.copy(isDefault = true)
        val presets = collection.presets.map {
            when (it.id) {
                target.id -> updatedTarget
                else -> if (it.isDefault) it.copy(isDefault = false) else it
            }
        }
        writeCollection(
            collection.copy(
                formatVersion = FORMAT_VERSION,
                defaultPresetId = target.id,
                presets = presets,
            ),
        )
        return updatedTarget
    }

    fun archive(
        id: String,
        usage: PersonaPresetUsage,
    ): PersonaPreset {
        ensureMigrated()
        val collection = readCollection()
        val existing = collection.presets.firstOrNull { it.id == id }
            ?: throw PersonaPresetException(
                PersonaPresetErrorCode.PRESET_NOT_FOUND,
            )
        if (
            existing.isDefault ||
            existing.id == collection.systemDefaultPresetId
        ) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.DEFAULT_PROTECTED,
            )
        }
        if (usage.usedByContact) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.CONTACT_REFERENCE_PROTECTED,
            )
        }
        if (existing.archived) {
            return existing
        }
        val archived = existing.copy(
            archivedAt = nowSeconds(),
            updatedAt = maxOf(nowSeconds(), existing.updatedAt + 1),
            revision = existing.revision + 1,
        )
        writeCollection(
            collection.copy(
                presets = collection.presets.map {
                    if (it.id == id) archived else it
                },
            ),
        )
        return archived
    }

    fun unarchive(id: String): PersonaPreset {
        ensureMigrated()
        val collection = readCollection()
        val existing = collection.presets.firstOrNull { it.id == id }
            ?: throw PersonaPresetException(
                PersonaPresetErrorCode.PRESET_NOT_FOUND,
            )
        if (!existing.archived) {
            return existing
        }
        val restored = existing.copy(
            archivedAt = null,
            updatedAt = maxOf(nowSeconds(), existing.updatedAt + 1),
            revision = existing.revision + 1,
        )
        writeCollection(
            collection.copy(
                presets = collection.presets.map {
                    if (it.id == id) restored else it
                },
            ),
        )
        return restored
    }

    fun hardDelete(
        id: String,
        usage: PersonaPresetUsage,
    ): PersonaPreset {
        ensureMigrated()
        val collection = readCollection()
        val existing = collection.presets.firstOrNull { it.id == id }
            ?: throw PersonaPresetException(
                PersonaPresetErrorCode.PRESET_NOT_FOUND,
            )
        if (
            existing.isDefault ||
            existing.id == collection.systemDefaultPresetId
        ) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.DEFAULT_PROTECTED,
            )
        }
        if (!existing.archived) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.DELETE_REQUIRES_ARCHIVE,
            )
        }
        if (usage.usedByContact) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.CONTACT_REFERENCE_PROTECTED,
            )
        }
        if (usage.hasMemoryScopeData) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.SCOPE_REFERENCE_PROTECTED,
            )
        }
        writeCollection(
            collection.copy(
                presets = collection.presets.filterNot { it.id == id },
            ),
        )
        return existing
    }

    private fun readLegacyProfile(): PersonaData {
        if (!legacyFile.isFile || legacyFile.length() == 0L) {
            return PersonaData("", emptyList())
        }
        return try {
            PersonaProfileCodec.decode(
                legacyFile.readText(Charsets.UTF_8),
            )
        } catch (error: Throwable) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.LEGACY_JSON_INVALID,
                error.message.orEmpty(),
            )
        }
    }

    private fun readCollection(): PersonaPresetCollection {
        return try {
            decodeCollection(repositoryFile.readText(Charsets.UTF_8))
        } catch (error: PersonaPresetException) {
            throw error
        } catch (error: Throwable) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.REPOSITORY_JSON_INVALID,
                error.message.orEmpty(),
            )
        }
    }

    private fun decodeCollection(raw: String): PersonaPresetCollection {
        val root = JSONObject(raw)
        val formatVersion = root.optInt("format_version", -1)
        if (formatVersion != 1 && formatVersion != FORMAT_VERSION) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.REPOSITORY_JSON_INVALID,
                "unsupported format_version=$formatVersion",
            )
        }
        val storedDefaultId = root.optString("default_preset_id")
        val systemDefaultId = if (formatVersion == 1) {
            storedDefaultId
        } else {
            root.optString("system_default_preset_id")
        }
        if (
            storedDefaultId.isBlank() ||
            systemDefaultId != defaultPersonaId
        ) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.INVALID_DEFAULT_ID,
            )
        }
        val array = root.optJSONArray("presets") ?: JSONArray()
        val presets = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw PersonaPresetException(
                        PersonaPresetErrorCode.REPOSITORY_JSON_INVALID,
                    )
                add(decodePreset(item, storedDefaultId))
            }
        }
        val defaultCount = presets.count { it.isDefault }
        if (defaultCount != 1) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.DEFAULT_PRESET_MISSING,
            )
        }
        if (presets.map { it.id }.distinct().size != presets.size) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.REPOSITORY_JSON_INVALID,
                "duplicate preset id",
            )
        }
        if (
            presets.none { it.id == systemDefaultId } ||
            presets.none { it.id == storedDefaultId && !it.archived }
        ) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.INVALID_DEFAULT_ID,
            )
        }
        return PersonaPresetCollection(
            formatVersion = formatVersion,
            systemDefaultPresetId = systemDefaultId,
            defaultPresetId = storedDefaultId,
            presets = presets,
        )
    }

    private fun decodePreset(
        item: JSONObject,
        defaultPresetId: String,
    ): PersonaPreset {
        val id = item.optString("id")
        requireUuid(id)
        val personaJson = item.optJSONObject("persona")
            ?: throw PersonaPresetException(
                PersonaPresetErrorCode.REPOSITORY_JSON_INVALID,
            )
        val persona = try {
            PersonaProfileCodec.decode(personaJson.toString())
        } catch (error: Throwable) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.REPOSITORY_JSON_INVALID,
                error.message.orEmpty(),
            )
        }
        return PersonaPreset(
            id = id,
            name = cleanName(item.optString("name")),
            persona = persona,
            createdAt = item.optLong("created_at"),
            updatedAt = item.optLong("updated_at", item.optLong("created_at")),
            archivedAt = if (item.isNull("archived_at")) {
                null
            } else {
                item.optLong("archived_at")
            },
            revision = item.optInt("revision", 1).coerceAtLeast(1),
            isDefault = id == defaultPresetId,
        )
    }

    private fun writeCollection(collection: PersonaPresetCollection) {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.STORAGE_FAILURE,
                "unable to create persona directory",
            )
        }
        val temporary = File(rootDirectory, "$REPOSITORY_FILE_NAME.tmp")
        try {
            temporary.writeText(
                encodeCollection(collection),
                Charsets.UTF_8,
            )
            runCatching {
                Files.move(
                    temporary.toPath(),
                    repositoryFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.recoverCatching {
                Files.move(
                    temporary.toPath(),
                    repositoryFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrThrow()
        } catch (error: PersonaPresetException) {
            throw error
        } catch (error: Throwable) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.STORAGE_FAILURE,
                error.message.orEmpty(),
            )
        } finally {
            temporary.delete()
        }
    }

    private fun encodeCollection(collection: PersonaPresetCollection): String {
        return JSONObject()
            .put("format_version", FORMAT_VERSION)
            .put(
                "system_default_preset_id",
                collection.systemDefaultPresetId,
            )
            .put("default_preset_id", collection.defaultPresetId)
            .put(
                "presets",
                JSONArray().apply {
                    collection.presets.forEach { preset ->
                        put(
                            JSONObject()
                                .put("id", preset.id)
                                .put("name", preset.name)
                                .put(
                                    "persona",
                                    JSONObject(
                                        PersonaProfileCodec.encode(
                                            preset.persona,
                                        ),
                                    ),
                                )
                                .put("created_at", preset.createdAt)
                                .put("updated_at", preset.updatedAt)
                                .put(
                                    "archived_at",
                                    preset.archivedAt ?: JSONObject.NULL,
                                )
                                .put("revision", preset.revision)
                                .put("is_default", preset.isDefault),
                        )
                    }
                },
            )
            .toString(2)
    }

    private fun cleanName(value: String): String {
        val name = value.trim()
        if (name.isBlank()) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.INVALID_NAME,
            )
        }
        if (name.length > MAX_NAME_CHARS) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.INVALID_NAME,
                "name is too long",
            )
        }
        return name
    }

    private fun newPresetId(existing: List<PersonaPreset>): String {
        repeat(8) {
            val value = newId()
            requireUuid(value)
            if (existing.none { it.id == value }) {
                return value
            }
        }
        throw PersonaPresetException(
            PersonaPresetErrorCode.INVALID_PRESET_ID,
            "unable to allocate a unique preset id",
        )
    }

    private fun requireUuid(value: String) {
        try {
            UUID.fromString(value)
        } catch (error: IllegalArgumentException) {
            throw PersonaPresetException(
                PersonaPresetErrorCode.INVALID_PRESET_ID,
                error.message.orEmpty(),
            )
        }
    }

    private fun duplicateName(source: String): String {
        val suffix = " 副本"
        return source.take(MAX_NAME_CHARS - suffix.length).trimEnd() + suffix
    }

    private data class PersonaPresetCollection(
        val formatVersion: Int,
        val systemDefaultPresetId: String,
        val defaultPresetId: String,
        val presets: List<PersonaPreset>,
    )

    companion object {
        const val FORMAT_VERSION = 2
        const val LEGACY_FILE_NAME = "profile.json"
        const val REPOSITORY_FILE_NAME = "persona-presets-v1.json"
        const val DEFAULT_NAME = "默认人格"
        private const val MAX_NAME_CHARS = 80

        fun forContext(context: Context): PersonaPresetRepository {
            return PersonaPresetRepository(
                rootDirectory = File(context.filesDir, "persona"),
                defaultPersonaId = MemoryV2IdentityStore(context).personaId,
            )
        }
    }
}
