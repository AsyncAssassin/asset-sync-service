package com.example.assetsync.application.outbox

import com.fasterxml.jackson.databind.JsonNode

data class OutboxEventLogFields(
    val transactionId: String,
    val chainId: String?,
    val address: String?,
    val asset: String?,
    val txHash: String?,
    val eventIndex: Int?,
    val transactionStatus: String?,
)

fun OutboxEvent.toLogFields(): OutboxEventLogFields =
    OutboxEventLogFields(
        transactionId = payload.textOrNull("transactionId") ?: aggregateId.toString(),
        chainId = payload.textOrNull("chainId"),
        address = payload.textOrNull("address"),
        asset = payload.textOrNull("asset"),
        txHash = payload.textOrNull("txHash"),
        eventIndex = payload.intOrNull("eventIndex"),
        transactionStatus = payload.textOrNull("status"),
    )

private fun JsonNode.textOrNull(fieldName: String): String? =
    get(fieldName)
        ?.takeUnless { it.isNull }
        ?.asText()
        ?.takeIf { it.isNotBlank() }

private fun JsonNode.intOrNull(fieldName: String): Int? =
    get(fieldName)
        ?.takeUnless { it.isNull }
        ?.takeIf { it.canConvertToInt() }
        ?.asInt()
