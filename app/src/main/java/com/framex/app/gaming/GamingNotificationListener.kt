package com.framex.app.gaming

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint

/**
 * Notification Listener Service that intercepts and cancels ALL incoming
 * notifications while Gaming Mode is active.
 *
 * Rationale: OriginOS sometimes bypasses DND for internal "System warnings"
 * and "Battery alerts".  This listener provides a second layer of suppression
 * that operates independently of the NotificationManager DND API.
 *
 * The service must be enabled by the user via
 * Settings → Apps → Special App Access → Notification Access.
 */
@AndroidEntryPoint
class GamingNotificationListener : NotificationListenerService() {

    /**
     * Called whenever a new notification is posted.
     * If Gaming Mode is active, cancel it immediately.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Do not touch our own gaming-mode notification — that would cause a loop.
        if (sbn.packageName == packageName && sbn.id == GamingModeService.NOTIFICATION_ID) return

        if (GamingModeEngine.isActive.value) {
            try {
                cancelNotification(sbn.key)
            } catch (e: Exception) {
                // Swallow — failing to cancel a notification is non-fatal.
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No action needed on removal.
    }
}
