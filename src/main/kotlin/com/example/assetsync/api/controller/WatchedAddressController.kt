package com.example.assetsync.api.controller

import com.example.assetsync.api.dto.UpdateWatchedAddressRequest
import com.example.assetsync.api.dto.WatchedAddressResponse
import com.example.assetsync.api.dto.toResponse
import com.example.assetsync.application.account.WatchedAddressApplicationService
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/addresses")
class WatchedAddressController(
    private val watchedAddressApplicationService: WatchedAddressApplicationService,
) {

    @PatchMapping("/{addressId}")
    fun updateWatchedAddress(
        @PathVariable addressId: UUID,
        @Valid @RequestBody request: UpdateWatchedAddressRequest,
    ): WatchedAddressResponse =
        watchedAddressApplicationService.updateStatus(addressId = addressId, status = request.toStatus()).toResponse()
}
