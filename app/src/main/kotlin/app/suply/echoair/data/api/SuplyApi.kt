package app.suply.echoair.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import okhttp3.ResponseBody

interface SuplyApi {

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    // Vision AI
    @POST("api/vision/identify-shipment")
    suspend fun identifyShipment(@Body body: VisionRequest): VisionResponse

    // Device QR lookup
    @GET("api/devices/lookup")
    suspend fun lookupDevice(
        @Query("identifier") identifier: String,
        @Query("include_shipment") includeShipment: Boolean = true
    ): DeviceLookupResponse

    // Shipment list (manual AWB autocomplete + ambient roster)
    @GET("api/shipments")
    suspend fun listShipments(
        @Query("status") status: String? = null,
        @Query("search") search: String? = null
    ): ShipmentListResponse

    @GET("api/shipments/{id}")
    suspend fun shipment(@Path("id") id: String): ShipmentDto

    // Primary telemetry submission
    @POST("api/echo-scan")
    suspend fun echoScan(@Body body: EchoScanRequest): EchoScanResponse

    // Report PDFs — returned as a stream for the WebView / PDF viewer
    @Streaming
    @POST("api/report/{shipmentId}/generate")
    suspend fun generateReport(
        @Path("shipmentId") shipmentId: String,
        @Body body: ReportRequest
    ): Response<ResponseBody>

    @Streaming
    @POST("api/report/{shipmentId}/insurance")
    suspend fun insuranceReport(
        @Path("shipmentId") shipmentId: String,
        @Body body: ReportRequest
    ): Response<ResponseBody>
}

@kotlinx.serialization.Serializable
data class ReportRequest(val lang: String = "en")
