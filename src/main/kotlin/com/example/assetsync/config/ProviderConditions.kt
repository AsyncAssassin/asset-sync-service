package com.example.assetsync.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * The one condition every HTTP-bridge-only bean shares: `asset-sync.provider.type=http`, which is
 * also the default when the property is absent. Composing it once keeps the bridge `RestClient`,
 * `HttpChainProvider`, and its health indicator from drifting apart when a provider type is added.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(prefix = "asset-sync.provider", name = ["type"], havingValue = "http", matchIfMissing = true)
annotation class ConditionalOnHttpChainProvider

/** The matching condition for every Alchemy-only bean: `asset-sync.provider.type=alchemy`. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(prefix = "asset-sync.provider", name = ["type"], havingValue = "alchemy")
annotation class ConditionalOnAlchemyChainProvider
