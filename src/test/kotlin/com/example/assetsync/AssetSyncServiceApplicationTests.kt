package com.example.assetsync

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class AssetSyncServiceApplicationTests {

    @Test
    fun contextLoads() {
    }
}
