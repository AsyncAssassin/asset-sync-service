package com.example.assetsync.infrastructure.provider

import com.example.assetsync.config.ConditionalOnHttpChainProvider
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Reflects the HTTP bridge provider's last observed connectivity rather than a constant UP.
 * Before any fetch it reports UP (nothing known yet); a failed fetch reports DOWN with the error.
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

    override fun health(): Health =
        when (httpChainProvider.lastFetchHealthy()) {
            null -> Health.up().withDetail("provider", "http").withDetail("state", "no-fetch-yet").build()
            true -> Health.up().withDetail("provider", "http").build()
            false -> Health.down()
                .withDetail("provider", "http")
                .withDetail("error", httpChainProvider.lastError() ?: "unknown")
                .build()
        }
}
