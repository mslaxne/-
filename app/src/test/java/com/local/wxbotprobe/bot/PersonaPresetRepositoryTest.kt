package com.local.wxbotprobe.bot

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonaPresetRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val defaultId = "11111111-1111-4111-8111-111111111111"
    private val firstNewId = "22222222-2222-4222-8222-222222222222"
    private val secondNewId = "33333333-3333-4333-8333-333333333333"

    @Test
    fun `legacy profile migrates without changing the legacy file`() {
        val root = temporary.newFolder("persona")
        val legacy = File(root, PersonaPresetRepository.LEGACY_FILE_NAME)
        val profile = PersonaData(
            instructions = "旧文件里的人格正文",
            examples = listOf("在吗" to "在"),
            identityMode = PersonaIdentityMode.TRANSPARENT_COMPANION,
            contactAddress = "同事",
            relationshipRole = "陪伴角色",
        )
        val original = PersonaProfileCodec.encode(profile)
        legacy.writeText(original, Charsets.UTF_8)
        var generatedIdCalls = 0
        val repository = repository(
            root,
            generated = {
                generatedIdCalls += 1
                firstNewId
            },
        )

        val migration = repository.ensureMigrated()

        assertEquals(defaultId, migration.preset.id)
        assertEquals(PersonaPresetRepository.DEFAULT_NAME, migration.preset.name)
        assertEquals(profile, migration.preset.persona)
        assertTrue(migration.preset.isDefault)
        assertTrue(migration.migratedLegacyFile)
        assertEquals(0, generatedIdCalls)
        assertEquals(original, legacy.readText(Charsets.UTF_8))
        assertEquals(
            JSONObject(
                File(
                    root,
                    PersonaPresetRepository.REPOSITORY_FILE_NAME,
                ).readText(Charsets.UTF_8),
            ).getInt("format_version"),
            2,
        )
    }

    @Test
    fun `empty and missing legacy files migrate to a blank default preset`() {
        val emptyRoot = temporary.newFolder("empty-persona")
        File(
            emptyRoot,
            PersonaPresetRepository.LEGACY_FILE_NAME,
        ).writeText("", Charsets.UTF_8)
        val emptyRepository = repository(emptyRoot)

        assertEquals(
            PersonaData("", emptyList()),
            emptyRepository.ensureMigrated().preset.persona,
        )

        val missingRoot = temporary.newFolder("missing-persona")
        val missingRepository = repository(missingRoot)

        assertEquals(
            PersonaData("", emptyList()),
            missingRepository.ensureMigrated().preset.persona,
        )
        assertFalse(
            File(
                missingRoot,
                PersonaPresetRepository.LEGACY_FILE_NAME,
            ).exists(),
        )
    }

    @Test
    fun `repeated migration is idempotent`() {
        val root = temporary.newFolder("repeat-persona")
        File(
            root,
            PersonaPresetRepository.LEGACY_FILE_NAME,
        ).writeText(
            PersonaProfileCodec.encode(PersonaData("稳定人格", emptyList())),
            Charsets.UTF_8,
        )
        val repository = repository(root)

        val first = repository.ensureMigrated()
        val second = repository.ensureMigrated()

        assertEquals(first.preset, second.preset)
        assertFalse(second.migratedLegacyFile)
        assertEquals(1, repository.list().size)
        assertFalse(
            File(
                root,
                "${PersonaPresetRepository.REPOSITORY_FILE_NAME}.tmp",
            ).exists(),
        )
    }

    @Test
    fun `default id is identity id and created ids are stable uuids`() {
        val root = temporary.newFolder("stable-persona")
        val repository = repository(root)
        val default = repository.ensureMigrated().preset
        val created = repository.create(
            "新人格",
            PersonaData("自然一点", listOf("你好" to "嗨")),
        )
        val reloaded = repository(root).get(created.id)

        assertEquals(defaultId, default.id)
        assertEquals(firstNewId, created.id)
        assertNotEquals(default.id, created.id)
        assertEquals(created, reloaded)
    }

    @Test
    fun `global default can switch while the identity anchor stays stable`() {
        val root = temporary.newFolder("switch-default-persona")
        val repository = repository(root)
        val anchor = repository.ensureMigrated().preset
        val created = repository.create(
            "当前全局人格",
            PersonaData("默认使用这一人格", emptyList()),
        )

        val selected = repository.setDefault(created.id)

        assertTrue(selected.isDefault)
        assertEquals(created.id, repository.ensureMigrated().preset.id)
        assertFalse(repository.get(anchor.id).isDefault)
        val rootJson = JSONObject(
            File(
                root,
                PersonaPresetRepository.REPOSITORY_FILE_NAME,
            ).readText(Charsets.UTF_8),
        )
        assertEquals(defaultId, rootJson.getString("system_default_preset_id"))
        assertEquals(created.id, rootJson.getString("default_preset_id"))
        assertEquals(2, rootJson.getInt("format_version"))

        try {
            repository.archive(created.id, PersonaPresetUsage())
            fail("current global default must not be archived")
        } catch (error: PersonaPresetException) {
            assertEquals(
                PersonaPresetErrorCode.DEFAULT_PROTECTED,
                error.code,
            )
        }
        try {
            repository.archive(anchor.id, PersonaPresetUsage())
            fail("identity anchor must not be archived")
        } catch (error: PersonaPresetException) {
            assertEquals(
                PersonaPresetErrorCode.DEFAULT_PROTECTED,
                error.code,
            )
        }
    }

    @Test
    fun `version one repository upgrades without changing its default`() {
        val root = temporary.newFolder("upgrade-persona-format")
        val file = File(root, PersonaPresetRepository.REPOSITORY_FILE_NAME)
        file.writeText(
            JSONObject()
                .put("format_version", 1)
                .put("default_preset_id", defaultId)
                .put(
                    "presets",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("id", defaultId)
                            .put("name", "默认人格")
                            .put(
                                "persona",
                                JSONObject(
                                    PersonaProfileCodec.encode(
                                        PersonaData("旧默认", emptyList()),
                                    ),
                                ),
                            )
                            .put("created_at", 1_000L)
                            .put("updated_at", 1_000L)
                            .put("archived_at", JSONObject.NULL)
                            .put("revision", 1)
                            .put("is_default", true),
                    ),
                )
                .toString(2),
            Charsets.UTF_8,
        )
        val repository = repository(root)

        val migrated = repository.ensureMigrated()

        assertEquals(defaultId, migrated.preset.id)
        assertFalse(migrated.migratedLegacyFile)
        val upgraded = JSONObject(file.readText(Charsets.UTF_8))
        assertEquals(2, upgraded.getInt("format_version"))
        assertEquals(
            defaultId,
            upgraded.getString("system_default_preset_id"),
        )
        assertEquals(defaultId, upgraded.getString("default_preset_id"))
    }

    @Test
    fun `preset crud and duplicate only copy persona content`() {
        val root = temporary.newFolder("crud-persona")
        val repository = repository(root)
        val created = repository.create(
            " 初稿 ",
            PersonaData(
                instructions = "安静",
                examples = listOf("早" to "早呀"),
                contactAddress = "同事",
            ),
        )
        assertEquals("初稿", created.name)

        val updated = repository.update(
            id = created.id,
            name = "定稿",
            persona = created.persona.copy(instructions = "温和"),
            expectedRevision = created.revision,
        )
        assertEquals("定稿", updated.name)
        assertEquals("温和", updated.persona.instructions)
        assertTrue(updated.revision > created.revision)

        val duplicate = repository.duplicate(updated.id)
        assertEquals("定稿 副本", duplicate.name)
        assertEquals(updated.persona, duplicate.persona)
        assertFalse(duplicate.isDefault)
        assertNull(duplicate.archivedAt)
        assertEquals(3, repository.list().size)

        try {
            repository.update(
                id = updated.id,
                name = "过期写入",
                persona = updated.persona,
                expectedRevision = created.revision,
            )
            fail("stale update should fail")
        } catch (error: PersonaPresetException) {
            assertEquals(
                PersonaPresetErrorCode.STALE_PRESET,
                error.code,
            )
        }
    }

    @Test
    fun `archive is soft and default archive is rejected`() {
        val root = temporary.newFolder("archive-persona")
        val repository = repository(root)
        val default = repository.ensureMigrated().preset
        val created = repository.create("可归档", PersonaData("", emptyList()))

        val archived = repository.archive(
            created.id,
            PersonaPresetUsage(),
        )

        assertTrue(archived.archived)
        assertEquals(emptyList<PersonaPreset>(), repository.list().filter {
            it.id == created.id
        })
        assertTrue(repository.list(includeArchived = true).any {
            it.id == created.id
        })
        assertEquals(archived, repository.get(created.id))
        try {
            repository.archive(default.id, PersonaPresetUsage())
            fail("default preset must not be archived")
        } catch (error: PersonaPresetException) {
            assertEquals(
                PersonaPresetErrorCode.DEFAULT_PROTECTED,
                error.code,
            )
        }
    }

    @Test
    fun `contact and scope references protect archive and hard delete`() {
        val root = temporary.newFolder("reference-persona")
        val repository = repository(root)
        repository.ensureMigrated()
        val contactUsed = repository.create(
            "联系人使用",
            PersonaData("", emptyList()),
        )

        try {
            repository.archive(
                contactUsed.id,
                PersonaPresetUsage(contactIds = setOf("contact-a")),
            )
            fail("contact referenced preset must not be archived")
        } catch (error: PersonaPresetException) {
            assertEquals(
                PersonaPresetErrorCode.CONTACT_REFERENCE_PROTECTED,
                error.code,
            )
        }

        val scopeUsed = repository.create(
            "有历史 scope",
            PersonaData("", emptyList()),
        )
        val archived = repository.archive(scopeUsed.id, PersonaPresetUsage())
        try {
            repository.hardDelete(
                archived.id,
                PersonaPresetUsage(scopeCount = 1),
            )
            fail("scope backed preset must not be hard deleted")
        } catch (error: PersonaPresetException) {
            assertEquals(
                PersonaPresetErrorCode.SCOPE_REFERENCE_PROTECTED,
                error.code,
            )
        }
        assertEquals(archived, repository.get(archived.id))
    }

    @Test
    fun `unused archived preset can be hard deleted`() {
        val root = temporary.newFolder("delete-persona")
        val repository = repository(root)
        repository.ensureMigrated()
        val preset = repository.create("空预设", PersonaData("", emptyList()))
        repository.archive(preset.id, PersonaPresetUsage())

        repository.hardDelete(preset.id, PersonaPresetUsage())

        assertEquals(1, repository.list(includeArchived = true).size)
        try {
            repository.get(preset.id)
            fail("deleted preset should be absent")
        } catch (error: PersonaPresetException) {
            assertEquals(
                PersonaPresetErrorCode.PRESET_NOT_FOUND,
                error.code,
            )
        }
    }

    @Test
    fun `malformed legacy json is preserved and never overwritten`() {
        val root = temporary.newFolder("bad-legacy-persona")
        val legacy = File(root, PersonaPresetRepository.LEGACY_FILE_NAME)
        legacy.writeText("{broken", Charsets.UTF_8)
        val repository = repository(root)

        try {
            repository.ensureMigrated()
            fail("malformed legacy json must fail migration")
        } catch (error: PersonaPresetException) {
            assertEquals(
                PersonaPresetErrorCode.LEGACY_JSON_INVALID,
                error.code,
            )
        }

        assertEquals("{broken", legacy.readText(Charsets.UTF_8))
        assertFalse(repository.repositoryExists())
    }

    @Test
    fun `malformed preset repository fails closed without touching legacy`() {
        val root = temporary.newFolder("bad-repository-persona")
        val legacy = File(root, PersonaPresetRepository.LEGACY_FILE_NAME)
        legacy.writeText(
            PersonaProfileCodec.encode(PersonaData("旧配置", emptyList())),
            Charsets.UTF_8,
        )
        val repository = repository(root)
        repository.ensureMigrated()
        val originalLegacy = legacy.readText(Charsets.UTF_8)
        File(
            root,
            PersonaPresetRepository.REPOSITORY_FILE_NAME,
        ).writeText("{broken", Charsets.UTF_8)

        try {
            repository.list()
            fail("malformed preset repository must fail closed")
        } catch (error: PersonaPresetException) {
            assertEquals(
                PersonaPresetErrorCode.REPOSITORY_JSON_INVALID,
                error.code,
            )
        }

        assertEquals(originalLegacy, legacy.readText(Charsets.UTF_8))
    }

    private fun repository(
        root: File,
        generated: () -> String = {
            if (File(
                    root,
                    PersonaPresetRepository.REPOSITORY_FILE_NAME,
                ).exists()
            ) {
                if (
                    File(
                        root,
                        PersonaPresetRepository.REPOSITORY_FILE_NAME,
                    ).readText(Charsets.UTF_8).contains(firstNewId)
                ) {
                    secondNewId
                } else {
                    firstNewId
                }
            } else {
                firstNewId
            }
        },
    ): PersonaPresetRepository {
        var now = 1_000L
        return PersonaPresetRepository(
            rootDirectory = root,
            defaultPersonaId = defaultId,
            nowSeconds = {
                now += 1
                now
            },
            newId = generated,
        )
    }
}
