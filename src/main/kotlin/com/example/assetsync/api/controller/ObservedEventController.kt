package com.example.assetsync.api.controller

import com.example.assetsync.api.dto.IngestObservedEventRequest
import com.example.assetsync.api.dto.ObservedEventResponse
import com.example.assetsync.api.dto.toCommand
import com.example.assetsync.api.dto.toResponse
import com.example.assetsync.application.transaction.ObservedEventApplicationService
import com.example.assetsync.domain.model.TransitionOutcome
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/observed-events")
class ObservedEventController(
    private val observedEventApplicationService: ObservedEventApplicationService,
) {

    @PostMapping
    fun ingestObservedEvent(
        @Valid @RequestBody request: IngestObservedEventRequest,
    ): ResponseEntity<ObservedEventResponse> {
        val result = observedEventApplicationService.ingest(request.toCommand())
        val status = if (result.result == TransitionOutcome.CREATED) {
            HttpStatus.CREATED
        } else {
            HttpStatus.OK
        }

        return ResponseEntity
            .status(status)
            .body(result.toResponse())
    }
}
