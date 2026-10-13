package pl.zse.bydgoszcz.elektron.work

import javax.inject.Inject

/**
 * Wariant github: bez Firebase, więc bez pushy. Powiadomienia o zastępstwach
 * i ogłoszeniach przychodzą z synchronizacji w tle (SyncWorker, co 15 min).
 */
class NoPushTopics @Inject constructor() : PushTopics {
    override fun start() = Unit
}
