package com.local.wxbotprobe

import android.content.Context
import com.local.wxbotprobe.memory.v2.MemoryV2AdminStore

object WhitelistPolicy {
    fun existingOnly(
        savedIdentityKeys: Set<String>,
        existingIdentityKeys: Set<String>,
    ): Set<String> {
        return savedIdentityKeys.intersect(existingIdentityKeys)
    }

    fun without(
        savedIdentityKeys: Set<String>,
        identityKey: String,
    ): Set<String> {
        return savedIdentityKeys - identityKey
    }
}

object WhitelistContacts {
    fun current(context: Context): Set<String> {
        val existing = MemoryV2AdminStore(context).contacts()
            .mapTo(mutableSetOf()) { it.identityKey }
        return reconcile(context, existing)
    }

    fun reconcile(
        context: Context,
        existingIdentityKeys: Set<String>,
    ): Set<String> {
        val saved = SendSettings.whitelist(context)
        val active = WhitelistPolicy.existingOnly(saved, existingIdentityKeys)
        if (active != saved) {
            SendSettings.setWhitelist(context, active)
        }
        return active
    }

    fun remove(context: Context, identityKey: String): Boolean {
        val saved = SendSettings.whitelist(context)
        val active = WhitelistPolicy.without(saved, identityKey)
        if (active == saved) {
            return false
        }
        SendSettings.setWhitelist(context, active)
        return true
    }
}
