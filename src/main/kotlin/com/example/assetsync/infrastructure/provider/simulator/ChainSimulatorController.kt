package com.example.assetsync.infrastructure.provider.simulator

import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.infrastructure.provider.ProviderEvent
import com.example.assetsync.infrastructure.provider.ProviderEventsPageResponse
import jakarta.validation.constraints.Min
import java.math.BigDecimal
import org.springframework.context.annotation.Profile
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * In-process chain simulator for the human-facing `demo` profile only. `HttpChainProvider` (active
 * under demo) calls this over real HTTP, so a `demo` sync exercises the full provider path end to
 * end without any external dependency. Returns a deterministic confirmed event per address so a
 * sync always produces an observed transaction and a lifecycle outbox event. Request parameters are
 * bean-validated, so a non-positive `limit` is a 400 ProblemDetail like any API validation failure.
 *
 * The e2e tests do NOT use this controller — they point the provider at a WireMock stub via
 * `@DynamicPropertySource` (see the e2e test), which is deterministic and needs no fixed port.
 */
@RestController
@Profile("demo")
@Validated
class ChainSimulatorController {

    @GetMapping("/simulator/v1/chains/{chainId}/addresses/{address}/events")
    fun events(
        @PathVariable chainId: String,
        @PathVariable address: String,
        @RequestParam asset: String,
        @RequestParam @Min(1) limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ProviderEventsPageResponse {
        val finalCursor = cursor ?: "sim:" + Integer.toHexString(("$chainId:$address:$asset").hashCode())
        return ProviderEventsPageResponse(
            events = if (cursor == null) {
                listOf(
                    ProviderEvent(
                        txHash = "0xsim-" + Integer.toHexString(("$chainId:$address:$asset").hashCode()),
                        eventIndex = 0,
                        address = address,
                        asset = asset,
                        amount = BigDecimal("1.000000000000000000"),
                        blockHeight = 1_000,
                        confirmations = 6,
                        direction = Direction.INBOUND,
                        status = TransactionStatus.CONFIRMED,
                    ),
                )
            } else {
                emptyList()
            },
            nextCursor = finalCursor,
            hasMore = false,
            latestBlockHeight = 1_000,
            safeBlockHeight = 1_000,
        )
    }
}
