package pl.zse.bydgoszcz.elektron.data.update

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import pl.zse.bydgoszcz.elektron.domain.repository.UpdateInstaller

/** Wariant foss: bezpośrednia instalacja aktualizacji z GitHub Releases w aplikacji. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds
    abstract fun bindUpdateInstaller(impl: ApkUpdateInstaller): UpdateInstaller
}
