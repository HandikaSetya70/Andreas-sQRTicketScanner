package com.dicoding.qrticketscanner.data.API

import com.dicoding.qrticketscanner.data.ValidationRequest
import com.dicoding.qrticketscanner.data.ValidationResponse
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface TicketApiService {
    @Headers(
        "Content-Type: application/json",
        "Accept: application/json"
    )
    @POST("api/tickets/validate")
    fun validateTicket(@Body request: ValidationRequest): Call<ValidationResponse>
}

