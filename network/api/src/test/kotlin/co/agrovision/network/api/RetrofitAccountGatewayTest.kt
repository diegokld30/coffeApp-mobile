package co.agrovision.network.api

import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.identity.ProducerProfile
import co.agrovision.core.model.port.ProfileRegistration
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Contrato de `productores/me` y de los códigos que la app trata aparte. */
class RetrofitAccountGatewayTest {

    private val api = MockWebServer()
    private lateinit var gateway: RetrofitAccountGateway
    private lateinit var syncGateway: RetrofitSyncGateway

    private val profile = ProducerProfile(
        fullName = "Carlos Arango Ríos",
        farmName = "La Esperanza",
        vereda = "El Cedro",
        municipality = "Pitalito",
        trainingConsent = true,
    )

    @BeforeEach
    fun setUp() {
        api.start()
        val baseUrl = api.url("/api/").toString()
        val client = OkHttpClient()
        val retrofit = retrofit2.Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType())
            )
            .build()
        val service = retrofit.create(AgroVisionApiService::class.java)
        gateway = RetrofitAccountGateway(service, Dispatchers.Unconfined)
        syncGateway = RetrofitSyncGateway(
            api = service,
            publicApi = retrofit.create(PublicApiService::class.java),
            client = client,
            baseUrl = baseUrl,
            io = Dispatchers.Unconfined,
        )
    }

    @AfterEach
    fun tearDown() = api.shutdown()

    @Test
    fun `el perfil viaja con los cinco campos exactos`() = runBlocking<Unit> {
        api.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

        val result = gateway.saveProducerProfile(profile)

        val request = api.takeRequest()
        assertEquals("/api/productores/me", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("nombre", "finca", "vereda", "municipio", "consentimiento"), body.keys)
        assertEquals(AgroResult.success(ProfileRegistration.CREATED), result)
    }

    @Test
    fun `un 409 significa que el perfil ya estaba registrado`() = runBlocking<Unit> {
        api.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"message":"Tu perfil de productor ya fue registrado"}""")
        )

        val result = gateway.saveProducerProfile(profile)

        assertEquals(AgroResult.success(ProfileRegistration.ALREADY_REGISTERED), result)
    }

    @Test
    fun `un 401 en la API se traduce a sesion vencida`() = runBlocking<Unit> {
        api.enqueue(MockResponse().setResponseCode(401))
        api.enqueue(MockResponse().setResponseCode(401))

        assertEquals(AgroResult.failure(DomainError.NotSignedIn), gateway.fetchProducerProfile())
        assertEquals(AgroResult.failure(DomainError.NotSignedIn), syncGateway.fetchMyResolutions(null))
    }
}
