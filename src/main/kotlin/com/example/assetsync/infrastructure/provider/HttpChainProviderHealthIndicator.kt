package com.example.assetsync.infrastructure.provider

import com.example.assetsync.config.ConditionalOnHttpChainProvider
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Reflects the HTTP bridge provider's last observed connectivity rather than a constant UP.
 * Before any fetch it reports UP (nothing known yet); a transport failure (timeout, 5xx, 429, a
 * connection error) reports DOWN with the error. A data error for one address, such as a 4xx or
 * malformed JSON, keeps the state and shows up as `lastDataError`, so one bad address cannot turn
 * the aggregate health into 503; `asset.sync.provider.pages` counts those pages as `MALFORMED`.
 * It contributes to the aggregate `/actuator/health` but is intentionally not in the readiness
 * group, so a transient provider outage does not flap liveness/readiness probes. It shares the
 * HTTP provider condition, so `type=alchemy` never asks for an `HttpChainProvider` bean.
 */
@Component
@Profile("!local & !test")
@ConditionalOnHttpChainProvider
class HttpChainProviderHealthIndicator(
    private val httpChainProvider: HttpChainProvider,
) : HealthIndicator {

    override fun health(): Health {
        val builder = when (httpChainProvider.lastFetchHealthy()) {
            null -> Health.up().withDetail("provider", "http").withDetail("state", "no-fetch-yet")
            true -> Health.up().withDetail("provider", "http")
            false -> Health.down()
                .withDetail("provider", "http")
                .withDetail("error", httpChainProvider.lastError() ?: "unknown")
        }
        httpChainProvider.lastDataError()?.let { builder.withDetail("lastDataError", it) }
        return builder.build()
    }
}
