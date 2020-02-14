/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.riotx.features.crypto.verification

import android.content.Context
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.session.crypto.sas.VerificationService
import im.vector.matrix.android.api.session.crypto.sas.VerificationTransaction
import im.vector.matrix.android.api.session.crypto.sas.VerificationTxState
import im.vector.matrix.android.internal.crypto.verification.PendingVerificationRequest
import im.vector.riotx.R
import im.vector.riotx.core.platform.VectorBaseActivity
import im.vector.riotx.features.home.room.detail.RoomDetailActivity
import im.vector.riotx.features.home.room.detail.RoomDetailArgs
import im.vector.riotx.features.popup.PopupAlertManager
import im.vector.riotx.features.themes.ThemeUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens to the VerificationManager and add a new notification when an incoming request is detected.
 */
@Singleton
class IncomingVerificationRequestHandler @Inject constructor(private val context: Context) : VerificationService.VerificationListener {

    private var session: Session? = null

    fun start(session: Session) {
        this.session = session
        session.cryptoService().verificationService().addListener(this)
    }

    fun stop() {
        session?.cryptoService()?.verificationService()?.removeListener(this)
        this.session = null
    }

    override fun transactionCreated(tx: VerificationTransaction) {}

    override fun transactionUpdated(tx: VerificationTransaction) {
        if (!tx.isToDeviceTransport()) return
        // TODO maybe check also if
        val uid = "kvr_${tx.transactionId}"
        when (tx.state) {
            is VerificationTxState.OnStarted       -> {
                // Add a notification for every incoming request
                val name = session?.getUser(tx.otherUserId)?.displayName
                        ?: tx.otherUserId

                val alert = PopupAlertManager.VectorAlert(
                        uid,
                        context.getString(R.string.sas_incoming_request_notif_title),
                        context.getString(R.string.sas_incoming_request_notif_content, name),
                        R.drawable.shield,
                        shouldBeDisplayedIn = { activity ->
                            if (activity is VectorBaseActivity) {
                                // TODO a bit too hugly :/
                                activity.supportFragmentManager.findFragmentByTag(VerificationBottomSheet.WAITING_SELF_VERIF_TAG)?.let {
                                    false.also {
                                        PopupAlertManager.cancelAlert(uid)
                                    }
                                } ?: true
                            } else true
                        })
                        .apply {
                            contentAction = Runnable {
                                (weakCurrentActivity?.get() as? VectorBaseActivity)?.let {
                                    it.navigator.performDeviceVerification(it, tx.otherUserId, tx.transactionId)
                                }
                            }
                            dismissedAction = Runnable {
                                tx.cancel()
                            }
                            addButton(
                                    context.getString(R.string.ignore),
                                    Runnable {
                                        tx.cancel()
                                    }
                            )
                            addButton(
                                    context.getString(R.string.action_open),
                                    Runnable {
                                        (weakCurrentActivity?.get() as? VectorBaseActivity)?.let {
                                            it.navigator.performDeviceVerification(it, tx.otherUserId, tx.transactionId)
                                        }
                                    }
                            )
                            // 10mn expiration
                            expirationTimestamp = System.currentTimeMillis() + (10 * 60 * 1000L)
                        }
                PopupAlertManager.postVectorAlert(alert)
            }
            is VerificationTxState.TerminalTxState -> {
                // cancel related notification
                PopupAlertManager.cancelAlert(uid)
            }
            else                                   -> Unit
        }
    }

    override fun markedAsManuallyVerified(userId: String, deviceId: String) {
    }

    override fun verificationRequestCreated(pr: PendingVerificationRequest) {
        // For incoming request we should prompt (if not in activity where this request apply)
        if (pr.isIncoming) {
            val name = session?.getUser(pr.otherUserId)?.displayName
                    ?: pr.otherUserId

            val alert = PopupAlertManager.VectorAlert(
                    uniqueIdForVerificationRequest(pr),
                    context.getString(R.string.sas_incoming_request_notif_title),
                    "$name(${pr.otherUserId})",
                    R.drawable.ic_shield_black,
                    shouldBeDisplayedIn = { activity ->
                        if (activity is RoomDetailActivity) {
                            activity.intent?.extras?.getParcelable<RoomDetailArgs>(RoomDetailActivity.EXTRA_ROOM_DETAIL_ARGS)?.let {
                                it.roomId != pr.roomId
                            } ?: true
                        } else true
                    })
                    .apply {
                        contentAction = Runnable {
                            (weakCurrentActivity?.get() as? VectorBaseActivity)?.let {
                                val roomId = pr.roomId
                                if (roomId.isNullOrBlank()) {
                                    it.navigator.waitSessionVerification(it)
                                } else {
                                    it.navigator.openRoom(it, roomId, pr.transactionId)
                                }
                            }
                        }
                        dismissedAction = Runnable {
                            session?.cryptoService()?.verificationService()?.declineVerificationRequestInDMs(pr.otherUserId,
                                    pr.requestInfo?.fromDevice ?: "",
                                    pr.transactionId ?: "",
                                    pr.roomId ?: ""
                            )
                        }
                        colorInt = ThemeUtils.getColor(context, R.attr.vctr_notice_secondary)
                        // 5mn expiration
                        expirationTimestamp = System.currentTimeMillis() + (5 * 60 * 1000L)
                    }
            PopupAlertManager.postVectorAlert(alert)
        }
    }

    override fun verificationRequestUpdated(pr: PendingVerificationRequest) {
        // If an incoming request is readied (by another device?) we should discard the alert
        if (pr.isIncoming && (pr.isReady || pr.handledByOtherSession)) {
            PopupAlertManager.cancelAlert(uniqueIdForVerificationRequest(pr))
        }
        super.verificationRequestUpdated(pr)
    }

    private fun uniqueIdForVerificationRequest(pr: PendingVerificationRequest) =
            "verificationRequest_${pr.transactionId}"
}
