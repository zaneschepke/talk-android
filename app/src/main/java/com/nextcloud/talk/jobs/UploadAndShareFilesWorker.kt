/*
 * Nextcloud Talk application
 *
 * @author Marcel Hibbe
 * Copyright (C) 2021-2022 Marcel Hibbe <dev@mhibbe.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextcloud.talk.jobs

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import autodagger.AutoInjector
import com.nextcloud.talk.R
import com.nextcloud.talk.activities.MainActivity
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.upload.chunked.ChunkedFileUploader
import com.nextcloud.talk.upload.chunked.OnDataTransferProgressListener
import com.nextcloud.talk.upload.normal.FileUploader
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.FileUtils
import com.nextcloud.talk.utils.NotificationUtils
import com.nextcloud.talk.utils.RemoteFileUtils
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_FROM_NOTIFICATION_START_CALL
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_INTERNAL_USER_ID
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_TOKEN
import com.nextcloud.talk.utils.database.user.CapabilitiesUtilNew
import com.nextcloud.talk.utils.permissions.PlatformPermissionUtil
import com.nextcloud.talk.utils.preferences.AppPreferences
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class UploadAndShareFilesWorker(val context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters), OnDataTransferProgressListener {

    @Inject
    lateinit var ncApi: NcApi

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var platformPermissionUtil: PlatformPermissionUtil

    lateinit var fileName: String

    private var mNotifyManager: NotificationManager? = null
    private var mBuilder: NotificationCompat.Builder? = null
    private lateinit var notification: Notification
    private var notificationId: Int = 0

    lateinit var roomToken: String
    lateinit var conversationName: String
    lateinit var currentUser: User
    private var isChunkedUploading = false
    private var file: File? = null
    private var chunkedFileUploader: ChunkedFileUploader? = null

    @Suppress("Detekt.TooGenericExceptionCaught")
    override fun doWork(): Result {
        NextcloudTalkApplication.sharedApplication!!.componentApplication.inject(this)

        if (!platformPermissionUtil.isFilesPermissionGranted()) {
            Log.w(
                TAG,
                "Storage permission is not granted. As a developer please make sure you check for" +
                    "permissions via UploadAndShareFilesWorker.isStoragePermissionGranted() and " +
                    "UploadAndShareFilesWorker.requestStoragePermission() beforehand. If you already " +
                    "did but end up with this warning, the user most likely revoked the permission"
            )
        }

        return try {
            currentUser = userManager.currentUser.blockingGet()
            val sourceFile = inputData.getString(DEVICE_SOURCE_FILE)
            roomToken = inputData.getString(ROOM_TOKEN)!!
            conversationName = inputData.getString(CONVERSATION_NAME)!!
            val metaData = inputData.getString(META_DATA)

            checkNotNull(currentUser)
            checkNotNull(sourceFile)
            require(sourceFile.isNotEmpty())
            checkNotNull(roomToken)

            val sourceFileUri = Uri.parse(sourceFile)
            fileName = FileUtils.getFileName(sourceFileUri, context)
            file = FileUtils.getFileFromUri(context, sourceFileUri)
            val remotePath = getRemotePath(currentUser)
            val uploadSuccess: Boolean

            initNotificationSetup()
            file?.let { isChunkedUploading = it.length() > CHUNK_UPLOAD_THRESHOLD_SIZE }
            if (file == null) {
                uploadSuccess = false
            } else if (isChunkedUploading) {
                Log.d(TAG, "starting chunked upload because size is " + file!!.length())

                initNotificationWithPercentage()
                val mimeType = context.contentResolver.getType(sourceFileUri)?.toMediaTypeOrNull()

                chunkedFileUploader = ChunkedFileUploader(
                    okHttpClient,
                    currentUser,
                    roomToken,
                    metaData,
                    this
                )

                uploadSuccess = chunkedFileUploader!!.upload(
                    file!!,
                    mimeType,
                    remotePath
                )
            } else {
                Log.d(TAG, "starting normal upload (not chunked) of $fileName")

                uploadSuccess = FileUploader(
                    context,
                    currentUser,
                    roomToken,
                    ncApi
                ).upload(
                    sourceFileUri,
                    fileName,
                    remotePath,
                    metaData
                ).blockingFirst()
            }

            if (uploadSuccess) {
                cancelNotification()
                return Result.success()
            } else if (isStopped) {
                // since work is cancelled the result would be ignored anyways
                return Result.failure()
            }

            Log.e(TAG, "Something went wrong when trying to upload file")
            showFailedToUploadNotification()
            return Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Something went wrong when trying to upload file", e)
            showFailedToUploadNotification()
            return Result.failure()
        }
    }

    private fun getRemotePath(currentUser: User): String {
        var remotePath = CapabilitiesUtilNew.getAttachmentFolder(currentUser)!! + "/" + fileName
        remotePath = RemoteFileUtils.getNewPathIfFileExists(
            ncApi,
            currentUser,
            remotePath
        )
        return remotePath
    }

    override fun onTransferProgress(percentage: Int) {
        notification = mBuilder!!
            .setProgress(HUNDRED_PERCENT, percentage, false)
            .setContentText(getNotificationContentText(percentage))
            .build()

        mNotifyManager!!.notify(notificationId, notification)
    }

    override fun onStopped() {
        if (file != null && isChunkedUploading) {
            chunkedFileUploader?.abortUpload {
                mNotifyManager?.cancel(notificationId)
            }
        }
        super.onStopped()
    }

    private fun initNotificationSetup() {
        mNotifyManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mBuilder = NotificationCompat.Builder(
            context,
            NotificationUtils.NotificationChannels
                .NOTIFICATION_CHANNEL_UPLOADS.name
        )
    }

    private fun initNotificationWithPercentage() {
        notification = mBuilder!!
            .setContentTitle(getResourceString(context, R.string.nc_upload_in_progess))
            .setContentText(getNotificationContentText(ZERO_PERCENT))
            .setSmallIcon(R.drawable.upload_white)
            .setOngoing(true)
            .setProgress(HUNDRED_PERCENT, ZERO_PERCENT, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(NotificationUtils.KEY_UPLOAD_GROUP)
            .setContentIntent(getIntentToOpenConversation())
            .addAction(
                R.drawable.ic_cancel_white_24dp, getResourceString(context, R.string.nc_cancel),
                getCancelUploadIntent()
            )
            .build()


        notificationId = SystemClock.uptimeMillis().toInt()
        mNotifyManager!!.notify(notificationId, notification)
        // only need one summary notification but multiple upload worker can call it more than once but it is safe
        // because of the same notification object config and id.
        makeSummaryNotification()
    }

    private fun makeSummaryNotification() {
        // summary notification encapsulating the group of notifications
        val summaryNotification = NotificationCompat.Builder(
            context, NotificationUtils.NotificationChannels
                .NOTIFICATION_CHANNEL_UPLOADS.name
        ).setSmallIcon(R.drawable.upload_white)
            .setGroup(NotificationUtils.KEY_UPLOAD_GROUP)
            .setGroupSummary(true)
            .build()

        mNotifyManager?.notify(NotificationUtils.GROUP_SUMMARY_NOTIFICATION_ID, summaryNotification)
    }

    private fun getActiveUploadNotifications(): Int? {
        // filter out active notifications that are upload notifications using group
        return mNotifyManager?.activeNotifications?.filter { notification.group == NotificationUtils.KEY_UPLOAD_GROUP }?.size
    }

    private fun cancelNotification() {
        mNotifyManager?.cancel(notificationId)
        // summary notification would not get dismissed automatically if child notifications are cancelled programmatically
        // so check if only 1 notification left if yes then cancel it because that would be summary notification
        if (getActiveUploadNotifications() == 1) {
            mNotifyManager?.cancel(NotificationUtils.GROUP_SUMMARY_NOTIFICATION_ID)
        }
    }

    private fun getNotificationContentText(percentage: Int): String {
        return String.format(
            getResourceString(context, R.string.nc_upload_notification_text),
            getShortenedFileName(),
            conversationName,
            percentage
        )
    }

    private fun getShortenedFileName(): String {
        return if (fileName.length > NOTIFICATION_FILE_NAME_MAX_LENGTH) {
            THREE_DOTS + fileName.takeLast(NOTIFICATION_FILE_NAME_MAX_LENGTH)
        } else {
            fileName
        }
    }

    private fun getCancelUploadIntent(): PendingIntent {
        return WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
    }

    private fun getIntentToOpenConversation(): PendingIntent? {
        val bundle = Bundle()
        val intent = Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK

        bundle.putString(KEY_ROOM_TOKEN, roomToken)
        bundle.putLong(KEY_INTERNAL_USER_ID, currentUser.id!!)
        bundle.putBoolean(KEY_FROM_NOTIFICATION_START_CALL, false)

        intent.putExtras(bundle)

        val requestCode = System.currentTimeMillis().toInt()
        val intentFlag: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getActivity(context, requestCode, intent, intentFlag)
    }

    private fun showFailedToUploadNotification() {
        val failureTitle = getResourceString(context, R.string.nc_upload_failed_notification_title)
        val failureText = String.format(
            getResourceString(context, R.string.nc_upload_failed_notification_text),
            fileName
        )
        val failureNotification = NotificationCompat.Builder(
            context, NotificationUtils.NotificationChannels
                .NOTIFICATION_CHANNEL_UPLOADS.name
        )
            .setContentTitle(failureTitle)
            .setContentText(failureText)
            .setSmallIcon(R.drawable.baseline_error_24)
            .setGroup(NotificationUtils.KEY_UPLOAD_GROUP)
            .setOngoing(false)
            .build()

        mNotifyManager?.cancel(notificationId)
        // update current notification with failure info
        mNotifyManager!!.notify(SystemClock.uptimeMillis().toInt(), failureNotification)
    }

    private fun getResourceString(context: Context, resourceId: Int): String {
        return context.resources.getString(resourceId)
    }

    companion object {
        private val TAG = UploadAndShareFilesWorker::class.simpleName
        private const val DEVICE_SOURCE_FILE = "DEVICE_SOURCE_FILE"
        private const val ROOM_TOKEN = "ROOM_TOKEN"
        private const val CONVERSATION_NAME = "CONVERSATION_NAME"
        private const val META_DATA = "META_DATA"
        private const val CHUNK_UPLOAD_THRESHOLD_SIZE: Long = 1024000
        private const val NOTIFICATION_FILE_NAME_MAX_LENGTH = 20
        private const val THREE_DOTS = "…"
        private const val HUNDRED_PERCENT = 100
        private const val ZERO_PERCENT = 0
        const val REQUEST_PERMISSION = 3123

        fun requestStoragePermission(activity: Activity) {
            when {
                Build.VERSION
                    .SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    activity.requestPermissions(
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_AUDIO
                        ),
                        REQUEST_PERMISSION
                    )
                }

                Build.VERSION.SDK_INT > Build.VERSION_CODES.Q -> {
                    activity.requestPermissions(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        ),
                        REQUEST_PERMISSION
                    )
                }

                else -> {
                    activity.requestPermissions(
                        arrayOf(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ),
                        REQUEST_PERMISSION
                    )
                }
            }
        }

        fun upload(fileUri: String, roomToken: String, conversationName: String, metaData: String?) {
            val data: Data = Data.Builder()
                .putString(DEVICE_SOURCE_FILE, fileUri)
                .putString(ROOM_TOKEN, roomToken)
                .putString(CONVERSATION_NAME, conversationName)
                .putString(META_DATA, metaData)
                .build()
            val uploadWorker: OneTimeWorkRequest = OneTimeWorkRequest.Builder(UploadAndShareFilesWorker::class.java)
                .setInputData(data)
                .build()
            WorkManager.getInstance().enqueueUniqueWork(fileUri, ExistingWorkPolicy.KEEP, uploadWorker)
        }
    }
}
