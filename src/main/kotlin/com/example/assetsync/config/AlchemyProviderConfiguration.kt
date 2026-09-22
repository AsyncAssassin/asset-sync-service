package com.example.assetsync.config

import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProvider
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProviderHealthIndicator
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyJsonRpcClient
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyPreflightReport
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyStartupPreflight
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/**
 * Every Alchemy-only bean lives here, behind the single `asset-sync.provider.type=alchemy`
 * condition, so an `http` deployment never binds, validates, or probes anything Alchemy-specific
 * and an `alchemy` deployment never needs the HTTP bridge `base-url`. The Alchemy `RestClient` is
 * separate and qualified: it shares the connect/read timeouts but has no base URL, because the
 * client builds per-network URIs from its own templates. `local`/`test` keep the fake provider.
 */
@Configuration
@Profile("!local & !test")
@ConditionalOnAlchemyChainProvider
@EnableConfigurationProperties(AlchemyProviderProperties::class)
class AlchemyProviderConfiguration {

    @Bean
    fun alchemyRestClient(providerProperties: ProviderProperties): RestClient {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(providerProperties.connectTimeout)
            setReadTimeout(providerProperties.readTimeout)
        }
        return RestClient.builder()
            .requestFactory(requestFactory)
            .build()
    }

    @Bean
    fun alchemyJsonRpcClient(
        @Qualifier("alchemyRestClient") alchemyRestClient: RestClient,
        properties: AlchemyProviderProperties,
        objectMapper: ObjectMapper,
        syncProperties: SyncProperties,
    ): AlchemyJsonRpcClient =
        AlchemyJsonRpcClient(
            restClient = alchemyRestClient,
            properties = properties,
            objectMapper = objectMapper,
            maxResponseBytes = syncProperties.pagination.maxProviderPageBytes,
        )

    /**
     * Runs during bean creation, after Liquibase (the `DSLContext` dependency already orders it and
     * the annotation makes that explicit), so a bad key or an unserved chain fails the context
     * before any lifecycle bean, including the sync worker, starts.
     */
    @Bean
    @DependsOnDatabaseInitialization
    fun alchemyPreflightReport(
        properties: AlchemyProviderProperties,
        dsl: DSLContext,
        alchemyJsonRpcClient: AlchemyJsonRpcClient,
    ): AlchemyPreflightReport =
        AlchemyStartupPreflight(properties = properties, dsl = dsl, client = alchemyJsonRpcClient).run()

    @Bean
    fun alchemyChainProvider(
        properties: AlchemyProviderProperties,
        alchemyPreflightReport: AlchemyPreflightReport,
    ): AlchemyChainProvider =
        AlchemyChainProvider(properties = properties, preflight = alchemyPreflightReport)

    @Bean
    fun alchemyChainProviderHealthIndicator(alchemyChainProvider: AlchemyChainProvider): AlchemyChainProviderHealthIndicator =
        AlchemyChainProviderHealthIndicator(alchemyChainProvider)
}
