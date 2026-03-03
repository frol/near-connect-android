package org.near.nearconnect

import org.json.JSONObject

/**
 * Represents an authenticated NEAR account.
 */
data class NEARAccount(
    val accountId: String,
    val publicKey: String?,
    /** Which wallet was used for this account (wallet id). */
    val walletId: String,
) {
    val displayName: String get() = accountId

    val shortDisplayName: String
        get() = if (accountId.length > 24) {
            "${accountId.take(12)}...${accountId.takeLast(8)}"
        } else {
            accountId
        }

    fun toJson(): String = JSONObject().apply {
        put("accountId", accountId)
        put("publicKey", publicKey ?: JSONObject.NULL)
        put("walletId", walletId)
    }.toString()

    companion object {
        fun fromJson(json: String): NEARAccount? = try {
            val obj = JSONObject(json)
            NEARAccount(
                accountId = obj.getString("accountId"),
                publicKey = obj.optString("publicKey").takeIf { it != "null" && it.isNotEmpty() },
                walletId = obj.getString("walletId"),
            )
        } catch (_: Exception) {
            null
        }
    }
}
