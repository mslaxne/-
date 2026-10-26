package com.local.wxbotprobe.bot

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

object PersonaProfileCodec {
    const val MAX_INSTRUCTIONS_CHARS = 0
    const val MAX_EXAMPLES = 0
    const val MAX_THEM_CHARS = 0
    const val MAX_ME_CHARS = 0

    fun encode(profile: PersonaData): String {
        val safe = sanitise(profile)
        return JSONObject()
            .put("version", 2)
            .put("instructions", safe.instructions)
            .put(
                "identity",
                JSONObject()
                    .put("mode", safe.identityMode.storedValue)
                    .put(
                        "question_policy",
                        safe.identityQuestionPolicy.storedValue,
                    )
                    .put("contact_address", safe.contactAddress)
                    .put("relationship_role", safe.relationshipRole),
            )
            .put("examples", JSONArray().apply {
                safe.examples.forEach { (them, me) ->
                    put(
                        JSONObject()
                            .put("them", them)
                            .put("me", me),
                    )
                }
            })
            .toString(2)
    }

    fun decode(raw: String): PersonaData {
        val root = JSONObject(raw)
        val identity = root.optJSONObject("identity") ?: JSONObject()
        val examples = mutableListOf<Pair<String, String>>()
        val array = root.optJSONArray("examples") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val them = item.optString("them")
            val me = item.optString("me")
            examples += them to me
        }
        return sanitise(
            PersonaData(
                instructions = root.optString("instructions"),
                examples = examples,
                identityMode = PersonaIdentityMode.fromStored(
                    identity.optString("mode"),
                ),
                identityQuestionPolicy = PersonaIdentityQuestionPolicy.fromStored(
                    identity.optString("question_policy"),
                ),
                contactAddress = identity.optString("contact_address"),
                relationshipRole = identity.optString("relationship_role"),
            ),
        )
    }

    fun sanitise(profile: PersonaData): PersonaData {
        val instructions = profile.instructions
            .replace("\r\n", "\n")
            .trim()
            .takeIfLimited(MAX_INSTRUCTIONS_CHARS)
        val examples = profile.examples.asSequence()
            .map { (them, me) ->
                them.replace("\r\n", "\n").trim()
                    .takeIfLimited(MAX_THEM_CHARS) to
                    me.replace("\r\n", "\n").trim()
                        .takeIfLimited(MAX_ME_CHARS)
            }
            .filter { (them, me) -> them.isNotEmpty() && me.isNotEmpty() }
            .toList()
            .let { if (MAX_EXAMPLES > 0) it.takeLast(MAX_EXAMPLES) else it }
        val identityMode = profile.identityMode
        val identityQuestionPolicy =
            if (identityMode == PersonaIdentityMode.AI_ASSISTANT) {
                PersonaIdentityQuestionPolicy.DISCLOSE_AI
            } else {
                profile.identityQuestionPolicy
            }
        return PersonaData(
            instructions = instructions,
            examples = examples,
            identityMode = identityMode,
            identityQuestionPolicy = identityQuestionPolicy,
            contactAddress = profile.contactAddress.trim(),
            relationshipRole = profile.relationshipRole.trim(),
        )
    }

    fun isConfigured(profile: PersonaData): Boolean {
        return profile.instructions.isNotBlank() ||
            profile.examples.isNotEmpty() ||
            profile.identityMode != PersonaIdentityMode.AI_ASSISTANT ||
            profile.contactAddress.isNotBlank() ||
            profile.relationshipRole.isNotBlank()
    }

    private fun String.takeIfLimited(limit: Int): String {
        return if (limit > 0) take(limit) else this
    }
}

class PersonaProfileStore(
    private val context: Context,
) {
    private val file = File(context.filesDir, "persona/profile.json")
    private val presets = PersonaPresetRepository.forContext(context)

    fun exists(): Boolean = file.isFile || presets.repositoryExists()

    fun load(): PersonaData {
        if (!exists()) {
            return PersonaData("", emptyList())
        }
        return runCatching {
            presets.ensureMigrated().preset.persona
        }.getOrElse {
            loadLegacyProfile()
        }
    }

    fun save(profile: PersonaData) {
        runCatching {
            presets.updateDefaultPersona(profile)
        }.getOrElse { error ->
            if (presets.repositoryExists()) {
                throw error
            }
            saveLegacyProfile(profile)
        }
    }

    fun loadDefaultPreset(): PersonaPreset? {
        return runCatching {
            if (presets.repositoryExists() || file.isFile) {
                presets.ensureMigrated().preset
            } else {
                null
            }
        }.getOrNull()
    }

    fun loadLegacyOnly(): PersonaData = loadLegacyProfile()

    fun legacyFile(): File = file

    private fun loadLegacyProfile(): PersonaData {
        if (!file.isFile || file.length() == 0L) {
            return PersonaData("", emptyList())
        }
        return runCatching {
            PersonaProfileCodec.decode(file.readText(Charsets.UTF_8))
        }.getOrDefault(PersonaData("", emptyList()))
    }

    private fun saveLegacyProfile(profile: PersonaData) {
        val directory = file.parentFile
        if (directory != null && !directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建人格配置目录。")
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(
            PersonaProfileCodec.encode(profile),
            Charsets.UTF_8,
        )
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrThrow()
    }
}
