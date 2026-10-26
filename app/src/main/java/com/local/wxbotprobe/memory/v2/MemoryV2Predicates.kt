package com.local.wxbotprobe.memory.v2

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

data class MemoryPredicateRule(
    val name: String,
    val cardinality: String,
    val allowA0: Boolean,
    val requiresViewpoint: Boolean,
    val requiredPermission: String?,
    val minLength: Int,
    val maxLength: Int,
)

class MemoryV2PredicateRegistry private constructor(
    private val rules: Map<String, MemoryPredicateRule>,
) {
    fun rule(name: String): MemoryPredicateRule {
        return rules[name] ?: throw MemoryV2Exception(
            MemoryV2ErrorCode.UNKNOWN_PREDICATE,
            name,
        )
    }

    fun normalisedValue(rule: MemoryPredicateRule, value: String): String {
        val trimmed = value.trim()
        if (trimmed.length !in rule.minLength..rule.maxLength) {
            throw MemoryV2Exception(MemoryV2ErrorCode.VALUE_TYPE_INVALID)
        }
        return if (rule.cardinality == "multi") {
            Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
        } else {
            trimmed
        }
    }

    companion object {
        fun load(context: Context): MemoryV2PredicateRegistry {
            val root = context.assets.open("memory_v2/predicates.json")
                .bufferedReader(Charsets.UTF_8)
                .use { JSONObject(it.readText()) }
            val predicates = root.getJSONObject("predicates")
            val rules = buildMap {
                predicates.keys().forEach { name ->
                    val item = predicates.getJSONObject(name)
                    val valueSchema = item.optJSONObject("value_schema") ?: JSONObject()
                    put(
                        name,
                        MemoryPredicateRule(
                            name = name,
                            cardinality = item.optString("cardinality", "single"),
                            allowA0 = item.optBoolean("allow_A0", false),
                            requiresViewpoint = item.optBoolean(
                                "requires_viewpoint",
                                false,
                            ),
                            requiredPermission = item.optString("requires")
                                .takeIf { it.isNotBlank() },
                            minLength = valueSchema.optInt("minLength", 1),
                            maxLength = valueSchema.optInt("maxLength", 2_048),
                        ),
                    )
                }
            }
            return MemoryV2PredicateRegistry(rules)
        }
    }
}
