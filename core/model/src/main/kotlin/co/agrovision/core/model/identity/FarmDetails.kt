package co.agrovision.core.model.identity

import co.agrovision.core.common.AgroConstants
import co.agrovision.core.model.review.ReviewRequest

/**
 * Variedad de café sembrada. Las que más se ven en fincas colombianas; lo que no
 * esté aquí va como [OTRA].
 *
 * La etiqueta vive en el dominio y no en recursos porque viaja al agrónomo dentro
 * de la nota de la solicitud: es texto del caso, no de la interfaz.
 */
enum class CoffeeVariety(val label: String) {
    CASTILLO("Castillo"),
    COLOMBIA("Colombia"),
    CENICAFE_1("Cenicafé 1"),
    TABI("Tabi"),
    CATURRA("Caturra"),
    TIPICA("Típica"),
    BORBON("Borbón"),
    GEISHA("Geisha"),
    OTRA("Otra"),
}

/** Tiempo aproximado desde la siembra. En rangos porque el productor lo sabe
 *  «más o menos», y la edad del árbol cambia el diagnóstico probable. */
enum class PlantingAge(val label: String) {
    MENOS_DE_1("Menos de 1 año"),
    DE_1_A_2("1 a 2 años"),
    DE_3_A_5("3 a 5 años"),
    DE_6_A_10("6 a 10 años"),
    MAS_DE_10("Más de 10 años"),
}

/**
 * Antepone variedad y edad del cultivo a la nota de la solicitud.
 *
 * `productores/me` acepta exactamente cinco campos y ninguno es la variedad ni la
 * edad, así que viajan en la nota: el agrónomo los lee en el mismo sitio donde
 * lee lo que escribió el productor. Se aplica al subir, no al crear, para que una
 * solicitud encolada antes de registrarse también los lleve.
 */
fun ReviewRequest.withFarmContext(account: Account): ReviewRequest {
    val context = listOfNotNull(
        account.coffeeVariety?.let { "Variedad: ${it.label}" },
        account.plantingAge?.let { "Sembrado hace: ${it.label}" },
    ).joinToString(" · ")
    if (context.isEmpty()) return this

    val note = listOfNotNull(context, noteText?.takeIf { it.isNotBlank() })
        .joinToString("\n\n")
        .take(AgroConstants.Review.MAX_NOTE_CHARS)
    return copy(noteText = note)
}
