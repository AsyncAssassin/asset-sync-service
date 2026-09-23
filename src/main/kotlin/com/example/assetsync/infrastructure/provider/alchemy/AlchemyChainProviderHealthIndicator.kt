package com.example.assetsync.infrastructure.provider.alchemy

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

/**
 * Mirrors the HTTP provider indicator for the Alchemy provider: UP after a successful startup
 * probe, DOWN after a fetch failed on availability or configuration (5xx, 429, a timeout, a
 * rejected key), with the scrubbed error. Invalid data for one address keeps the state and is
 * shown as `lastDataError`. Details name the provider, the auth mode, the probed networks, and the
 * state; never an endpoint, a header, or the API key. Like the HTTP indicator it stays out of the
 * readiness group so a provider outage does not flap probes.
 */
class AlchemyChainProviderHealthIndicator(
    private val provider: AlchemyChainProvider,
) : HealthIndicator {

    override fun health(): Health {
        val state = provider.state()
        val builder = if (state == AlchemyProviderState.FETCH_FAILED) Health.down() else Health.up()
        builder
            .withDetail("provider", "alchemy")
            .withDetail("authMode", provider.authMode.name.lowercase())
            .withDetail("networks", provider.networks)
            .withDetail("state", state.name.lowercase().replace('_', '-'))
        provider.lastError()?.let { builder.withDetail("error", it) }
        provider.lastDataError()?.let { builder.withDetail("lastDataError", it) }
        return builder.build()
    }
}
