package com.example.assetsync

import com.example.assetsync.AlchemyJsonRpcStubServer.RecordedRequest
import com.example.assetsync.AlchemyJsonRpcStubServer.StubResponse
import com.fasterxml.jackson.databind.JsonNode
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scripted chain behind [AlchemyJsonRpcStubServer]: a latest block, finality tags, and a list of
 * ERC-20 transfer rows that `alchemy_getAssetTransfers` filters by block range, direction, and
 * contract, sorted like Alchemy does and paged with `pageKey` after [pageSize] rows. Knobs
 * reproduce the provider hazards the adapter must survive: a repeated `pageKey`, a restarted page,
 * descending continuation order, and rows outside the requested block window.
 */
class ScriptedAlchemyChain(
    val watchedAddress: String,
    val contractAddress: String,
) {

    data class Transfer(
        val block: Long,
        val logIndex: Int,
        val from: String,
        val to: String,
        val hash: String = hashFor(block, logIndex),
        val rawValue: String = "0x12d687",
        val contract: String? = null,
        val decimal: String? = "0x6",
        val category: String = "erc20",
    )

    data class TransfersCall(
        val fromBlock: Long,
        val toBlock: Long,
        val direction: String,
        val pageKey: String?,
        val contractAddresses: List<String>,
        val params: JsonNode,
    )

    enum class PageKeyMode { NORMAL, REPEAT_KEY, RESTART_PAGE }

    @Volatile var latest: Long = 200
    val finality: MutableMap<String, Long?> = mutableMapOf("safe" to 180L, "finalized" to 170L)

    /** Tags absent from [finality] answer with JSON-RPC -32602 when true, with a null block otherwise. */
    @Volatile var unknownTagIsError: Boolean = false
    val transfers = mutableListOf<Transfer>()

    @Volatile var pageSize: Int = 1000
    @Volatile var descendingPages: Boolean = false
    @Volatile var pageKeyMode: PageKeyMode = PageKeyMode.NORMAL
    @Volatile var applyContractFilter: Boolean = true

    /** Appended to every one-block response, to simulate a provider window violation. */
    @Volatile var outOfWindowRow: Transfer? = null

    /** Runs before every transfers response, for example to move a test clock. */
    @Volatile var beforeTransfersResponse: (() -> Unit)? = null

    val transfersCalls = CopyOnWriteArrayList<TransfersCall>()

    fun responder(): (RecordedRequest) -> StubResponse = { request ->
        when (request.method) {
            "eth_blockNumber" -> AlchemyJsonRpcStubServer.result("\"${hex(latest)}\"")
            "eth_getBlockByNumber" -> {
                val tag = request.params?.get(0)?.asText()
                when {
                    tag != null && finality.containsKey(tag) ->
                        finality[tag]?.let { AlchemyJsonRpcStubServer.result("""{"number":"${hex(it)}","hash":"0xabc"}""") }
                            ?: AlchemyJsonRpcStubServer.result("null")
                    unknownTagIsError -> AlchemyJsonRpcStubServer.error(-32602, "invalid argument 0: unknown block tag")
                    else -> AlchemyJsonRpcStubServer.result("null")
                }
            }
            "alchemy_getAssetTransfers" -> transfersResponse(requireNotNull(request.params).get(0))
            else -> AlchemyJsonRpcStubServer.error(-32601, "method not found")
        }
    }

    private fun transfersResponse(params: JsonNode): StubResponse {
        beforeTransfersResponse?.invoke()
        val from = parseHex(params.get("fromBlock").asText())
        val to = parseHex(params.get("toBlock").asText())
        val toAddress = params.get("toAddress")?.asText()?.lowercase(Locale.ROOT)
        val fromAddress = params.get("fromAddress")?.asText()?.lowercase(Locale.ROOT)
        val requestedContracts = params.get("contractAddresses")?.map { it.asText().lowercase(Locale.ROOT) } ?: emptyList()
        val direction = if (toAddress != null) "in" else "out"
        val pageKey = params.get("pageKey")?.asText()
        transfersCalls += TransfersCall(from, to, direction, pageKey, requestedContracts, params)

        val rows = transfers
            .filter { it.block in from..to }
            .filter { toAddress == null || it.to.lowercase(Locale.ROOT) == toAddress }
            .filter { fromAddress == null || it.from.lowercase(Locale.ROOT) == fromAddress }
            .filter { !applyContractFilter || (it.contract ?: contractAddress).lowercase(Locale.ROOT) in requestedContracts }
            .sortedWith(compareBy({ it.block }, { it.logIndex }))
            .let { if (descendingPages) it.reversed() else it }
        val offset = when (pageKeyMode) {
            PageKeyMode.RESTART_PAGE -> 0
            else -> pageKey?.substringAfterLast(':')?.toInt() ?: 0
        }
        val slice = rows.drop(offset).take(pageSize).toMutableList()
        val nextOffset = offset + slice.size
        val nextPageKey = if (nextOffset < rows.size || (pageKey != null && pageKeyMode != PageKeyMode.NORMAL)) {
            val keyOffset = if (pageKeyMode == PageKeyMode.NORMAL) nextOffset else 1
            "pk:$direction:$from:$to:$keyOffset"
        } else {
            null
        }
        if (from == to) {
            outOfWindowRow?.let { slice += it }
        }
        val body = buildString {
            append("""{"transfers":[""")
            append(slice.joinToString(",") { rowJson(it) })
            append("]")
            nextPageKey?.let { append(""","pageKey":"$it"""") }
            append("}")
        }
        return AlchemyJsonRpcStubServer.result(body)
    }

    private fun rowJson(row: Transfer): String =
        """{"blockNum":"${hex(row.block)}","uniqueId":"${row.hash}:log:${row.logIndex}","hash":"${row.hash}",""" +
            """"from":"${row.from}","to":"${row.to}","value":1.234567,"erc721TokenId":null,"erc1155Metadata":null,"tokenId":null,""" +
            """"asset":"USDC","category":"${row.category}","rawContract":{"value":"${row.rawValue}","address":"${row.contract ?: contractAddress}"""" +
            (row.decimal?.let { ""","decimal":"$it"""" } ?: "") +
            """},"metadata":{"blockTimestamp":"2026-09-20T10:00:00.000Z"}}"""

    companion object {
        fun hex(value: Long): String = "0x" + java.lang.Long.toHexString(value)

        fun parseHex(value: String): Long = value.removePrefix("0x").toLong(radix = 16)

        fun hashFor(block: Long, logIndex: Int): String =
            "0x" + (block * 1000 + logIndex).toString(16).padStart(64, '0')
    }
}
