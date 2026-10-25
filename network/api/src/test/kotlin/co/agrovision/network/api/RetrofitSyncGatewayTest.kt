package co.agrovision.network.api

import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.capture.SyncStatus
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.port.UploadStatus
import co.agrovision.core.model.review.CaptureAngle
import co.agrovision.core.model.review.ResolutionType
import co.agrovision.core.model.review.ReviewImage
import co.agrovision.core.model.review.ReviewRequest
import co.agrovision.core.model.review.ReviewStatus
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Contrato con agrovision-api para solicitudes de revisión: URL por foto en
 * `imageUploads`, `mine?since=<epoch ms>` y el JWT limitado al host de la API.
 */
class RetrofitSyncGatewayTest {

    private val api = MockWebServer()
    private val storage = MockWebServer()
    private lateinit var gateway: RetrofitSyncGateway

    @BeforeEach
    fun setUp() {
        api.start()
        storage.start()
        val baseUrl = api.url("/api/").toString()
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor({ "jwt-de-prueba" }, baseUrl))
            .build()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val retrofit = retrofit2.Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        gateway = RetrofitSyncGateway(
            api = retrofit.create(AgroVisionApiService::class.java),
            publicApi = retrofit.create(PublicApiService::class.java),
            client = client,
            baseUrl = baseUrl,
            io = Dispatchers.Unconfined,
        )
    }

    @AfterEach
    fun tearDown() {
        api.shutdown()
        storage.shutdown()
    }

    @Test
    fun `lee una URL por foto de imageUploads`() = runBlocking<Unit> {
        api.enqueue(
            MockResponse().setBody(
                """
                {"results":[{"id":"req-1","serverId":"srv-1","status":"accepted","uploadUrl":null,
                  "imageUploads":[{"imageId":"img-1","uploadUrl":"https://s3/1"},
                                  {"imageId":"img-2","uploadUrl":"https://s3/2"}]}]}
                """.trimIndent()
            )
        )

        val outcome = (gateway.uploadReviewRequests(listOf(review())) as AgroResult.Success)
            .value.single()

        assertEquals("/api/v1/review-requests/batch", api.takeRequest().path)
        assertEquals(UploadStatus.ACCEPTED, outcome.status)
        assertNull(outcome.presignedImageUrl)
        assertEquals(mapOf("img-1" to "https://s3/1", "img-2" to "https://s3/2"), outcome.imageUploadUrls)
    }

    @Test
    fun `mine envia since en milisegundos y traduce estado y resolucion`() = runBlocking<Unit> {
        api.enqueue(
            MockResponse().setBody(
                """
                [{"id":"req-1","status":"RESOLVED","resolutionType":"NOT_A_PEST",
                  "agronomistResponse":"Es deficiencia de magnesio","resolvedAt":1700,"updatedAt":1800},
                 {"id":"req-2","status":"ASSIGNED"},
                 {"id":"req-3","status":"ALGO_NUEVO"}]
                """.trimIndent()
            )
        )

        val statuses = (gateway.fetchMyResolutions(1234L) as AgroResult.Success).value

        val request = api.takeRequest()
        assertEquals("/api/v1/review-requests/mine?since=1234", request.path)
        assertEquals("Bearer jwt-de-prueba", request.getHeader("Authorization"))
        // El estado desconocido se descarta en vez de sobrescribir la fila local.
        assertEquals(listOf("req-1", "req-2"), statuses.map { it.requestId })
        val resolved = statuses.first()
        assertEquals(ReviewStatus.RESOLVED, resolved.status)
        assertEquals(ResolutionType.NOT_A_PEST, resolved.resolution?.type)
        assertEquals(1800L, resolved.updatedAtEpochMillis)
        assertNull(statuses[1].resolution)
    }

    @Test
    fun `la foto va a la URL prefirmada sin el JWT del productor`() = runBlocking<Unit> {
        storage.enqueue(MockResponse().setResponseCode(200))
        val photo = File.createTempFile("foto", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val result = gateway.uploadImage(storage.url("/bucket/img-1?X-Amz-Signature=x").toString(), photo.path)

        val request = storage.takeRequest()
        assertEquals(AgroResult.unit, result)
        assertEquals("PUT", request.method)
        assertNull(request.getHeader("Authorization"))
        photo.delete()
    }

    private fun review() = ReviewRequest(
        id = "req-1",
        captureId = null,
        images = listOf("img-1", "img-2").map {
            ReviewImage(it, "/fotos/$it.jpg", "/fotos/$it.thumb", CaptureAngle.GENERAL, embedding = null)
        },
        cropType = CropType.CAFE,
        plantOrgan = PlantOrgan.HOJA,
        noteText = null,
        noteAudioPath = null,
        location = null,
        createdAtEpochMillis = 100L,
        status = ReviewStatus.PENDING_UPLOAD,
        resolution = null,
        sync = SyncStatus.PENDING,
    )
}
