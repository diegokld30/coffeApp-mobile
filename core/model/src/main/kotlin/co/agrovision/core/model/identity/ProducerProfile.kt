package co.agrovision.core.model.identity

/**
 * Perfil del productor (`productores/me`). El backend lo exige antes de aceptar
 * Solicitudes de Revisión: sin finca ni municipio, el agrónomo no tiene contexto
 * para dictaminar.
 *
 * Los rangos reproducen la validación del servidor. Se comprueban aquí para que
 * un campo corto se le señale al productor sin gastar un viaje de red.
 */
data class ProducerProfile(
    val fullName: String,
    val farmName: String,
    val vereda: String,
    val municipality: String,
    /** Autoriza el uso de sus fotos para entrenamiento (RF-10.2). */
    val trainingConsent: Boolean,
) {

    enum class Field(val lengths: IntRange) {
        FULL_NAME(3..150),
        FARM_NAME(2..150),
        VEREDA(2..100),
        MUNICIPALITY(2..100),
    }

    fun invalidFields(): Set<Field> = buildSet {
        if (fullName.trim().length !in Field.FULL_NAME.lengths) add(Field.FULL_NAME)
        if (farmName.trim().length !in Field.FARM_NAME.lengths) add(Field.FARM_NAME)
        if (vereda.trim().length !in Field.VEREDA.lengths) add(Field.VEREDA)
        if (municipality.trim().length !in Field.MUNICIPALITY.lengths) add(Field.MUNICIPALITY)
    }

    fun trimmed() = copy(
        fullName = fullName.trim(),
        farmName = farmName.trim(),
        vereda = vereda.trim(),
        municipality = municipality.trim(),
    )
}
