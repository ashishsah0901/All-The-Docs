package com.ashish.allthedocs.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

class IncomingDocuments {
    private val _pending = MutableStateFlow<CapturedFile?>(null)
    val pending: StateFlow<CapturedFile?> = _pending.asStateFlow()

    fun offer(file: CapturedFile) {
        _pending.value = file
    }

    private val _handoff = MutableStateFlow<CapturedFile?>(null)

    fun take(): CapturedFile? = _pending.getAndUpdate { null } ?: _handoff.getAndUpdate { null }

    fun handOff(file: CapturedFile) {
        _handoff.value = file
    }
}
