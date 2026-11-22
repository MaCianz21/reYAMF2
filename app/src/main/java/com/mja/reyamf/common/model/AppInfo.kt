package com.mja.reyamf.common.model

import android.content.pm.ActivityInfo
import android.os.Parcel
import android.os.Parcelable

data class AppInfo(
    val activityInfo: ActivityInfo,
    val userId: Int,
    val userName: String
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(activityInfo, flags)
        parcel.writeInt(userId)
        parcel.writeString(userName)
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<AppInfo> {
        override fun createFromParcel(parcel: Parcel): AppInfo {
            val activityInfo = parcel.readParcelable<ActivityInfo>(ActivityInfo::class.java.classLoader)
                ?: ActivityInfo()
            return AppInfo(
                activityInfo = activityInfo,
                userId = parcel.readInt(),
                userName = parcel.readString().orEmpty()
            )
        }

        override fun newArray(size: Int): Array<AppInfo?> = arrayOfNulls(size)
    }
}
