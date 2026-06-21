package com.example.assetsync.api.controller

import com.example.assetsync.api.dto.AccountResponse
import com.example.assetsync.api.dto.CreateAccountRequest
import com.example.assetsync.api.dto.RegisterWatchedAddressRequest
import com.example.assetsync.api.dto.WatchedAddressListResponse
import com.example.assetsync.api.dto.WatchedAddressResponse
import com.example.assetsync.api.dto.toResponse
import com.example.assetsync.application.account.AccountApplicationService
import com.example.assetsync.application.account.CreateAccountCommand
import com.example.assetsync.application.account.RegisterWatchedAddressCommand
import com.example.assetsync.application.account.WatchedAddressApplicationService
import jakarta.validation.Valid
import java.net.URI
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(
    private val accountApplicationService: AccountApplicationService,
    private val watchedAddressApplicationService: WatchedAddressApplicationService,
) {

    @PostMapping
    fun createAccount(
        @Valid @RequestBody request: CreateAccountRequest,
    ): ResponseEntity<AccountResponse> {
        val account = accountApplicationService.createAccount(
            CreateAccountCommand(externalRef = request.externalRef),
        )

        return ResponseEntity
            .created(URI.create("/api/v1/accounts/${account.id}"))
            .body(account.toResponse())
    }

    @GetMapping("/{accountId}")
    fun getAccount(
        @PathVariable accountId: UUID,
    ): AccountResponse =
        accountApplicationService.getAccount(accountId).toResponse()

    @PostMapping("/{accountId}/addresses")
    fun registerWatchedAddress(
        @PathVariable accountId: UUID,
        @Valid @RequestBody request: RegisterWatchedAddressRequest,
    ): ResponseEntity<WatchedAddressResponse> {
        val watchedAddress = watchedAddressApplicationService.registerWatchedAddress(
            RegisterWatchedAddressCommand(
                accountId = accountId,
                chainId = request.chainId,
                address = request.address,
                asset = request.asset,
                label = request.label,
            ),
        )

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(watchedAddress.toResponse())
    }

    @GetMapping("/{accountId}/addresses")
    fun listWatchedAddresses(
        @PathVariable accountId: UUID,
    ): WatchedAddressListResponse =
        WatchedAddressListResponse(
            items = watchedAddressApplicationService
                .listWatchedAddresses(accountId)
                .map { it.toResponse() },
        )
}
