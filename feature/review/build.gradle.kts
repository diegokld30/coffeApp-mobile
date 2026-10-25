plugins {
    alias(libs.plugins.agrovision.android.feature)
}

android {
    namespace = "co.agrovision.feature.review"
}

// Solicitudes de Revisión y el ingreso con OTP que exigen para enviarse.
//
// La cámara NO se importa de :feature:scan: la toma guiada la recibe como
// parámetro desde :app, igual que `ScanScreen`. Un feature que depende de otro
// feature es el primer paso para que dejen de poder compilarse por separado.
dependencies {
    // `BackHandler`: atrás cancela la solicitud y borra las tomas guiadas.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
}
