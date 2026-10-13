package pl.zse.bydgoszcz.elektron.work

/**
 * Subskrypcja powiadomień push. Implementacje zależą od wariantu (flavor):
 *  - gms  (GitHub): Firebase Cloud Messaging — `src/gms/.../FcmTopicManager`,
 *  - github: brak Firebase — `src/github/.../NoPushTopics`;
 *    powiadomienia i tak przychodzą z synchronizacji w tle (SyncWorker, co 15 min).
 */
interface PushTopics {
    fun start()
}
