package co.agrovision.network.api.di

import co.agrovision.core.model.port.AccountGateway
import co.agrovision.core.model.port.ArtifactFetcher
import co.agrovision.core.model.port.SyncGateway
import co.agrovision.network.api.OkHttpArtifactFetcher
import co.agrovision.network.api.RetrofitAccountGateway
import co.agrovision.network.api.RetrofitSyncGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GatewayBindings {

    @Binds
    @Singleton
    abstract fun bindSyncGateway(impl: RetrofitSyncGateway): SyncGateway

    @Binds
    @Singleton
    abstract fun bindAccountGateway(impl: RetrofitAccountGateway): AccountGateway

    @Binds
    @Singleton
    abstract fun bindArtifactFetcher(impl: OkHttpArtifactFetcher): ArtifactFetcher
}
