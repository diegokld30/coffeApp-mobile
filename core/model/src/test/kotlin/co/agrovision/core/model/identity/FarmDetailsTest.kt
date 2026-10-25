package co.agrovision.core.model.identity

import co.agrovision.core.common.AgroConstants
import co.agrovision.core.model.capture.SyncStatus
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.review.ReviewRequest
import co.agrovision.core.model.review.ReviewStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Variedad y edad del cafetal viajan al agrónomo al inicio de la nota. */
class FarmDetailsTest {

    private val account = Account.anonymous("device").copy(
        tier = UserTier.T2_VALIDADO,
        coffeeVariety = CoffeeVariety.CASTILLO,
        plantingAge = PlantingAge.DE_3_A_5,
    )

    @Test
    fun `antepone variedad y edad a la nota del productor`() {
        val sent = request(note = "Las hojas se caen desde hace una semana").withFarmContext(account)

        assertEquals(
            "Variedad: Castillo · Sembrado hace: 3 a 5 años\n\nLas hojas se caen desde hace una semana",
            sent.noteText,
        )
    }

    @Test
    fun `sin nota del productor queda solo el contexto`() {
        assertEquals(
            "Variedad: Castillo · Sembrado hace: 3 a 5 años",
            request(note = null).withFarmContext(account).noteText,
        )
    }

    @Test
    fun `sin datos del cafetal la solicitud no cambia`() {
        val original = request(note = "algo")
        assertSame(original, original.withFarmContext(Account.anonymous("device")))
    }

    @Test
    fun `la nota combinada respeta el limite`() {
        val long = "x".repeat(AgroConstants.Review.MAX_NOTE_CHARS)
        assertEquals(
            AgroConstants.Review.MAX_NOTE_CHARS,
            request(note = long).withFarmContext(account).noteText?.length,
        )
    }

    private fun request(note: String?) = ReviewRequest(
        id = "req-1",
        captureId = null,
        images = emptyList(),
        cropType = CropType.CAFE,
        plantOrgan = PlantOrgan.HOJA,
        noteText = note,
        noteAudioPath = null,
        location = null,
        createdAtEpochMillis = 0L,
        status = ReviewStatus.PENDING_UPLOAD,
        resolution = null,
        sync = SyncStatus.PENDING,
    )
}
