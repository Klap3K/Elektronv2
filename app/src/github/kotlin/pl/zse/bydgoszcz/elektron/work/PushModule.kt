package pl.zse.bydgoszcz.elektron.work

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Wariant github: bez powiadomień Firebase. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PushModule {
    @Binds
    abstract fun bindPushTopics(impl: NoPushTopics): PushTopics
}
