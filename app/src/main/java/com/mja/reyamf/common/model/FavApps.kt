package com.mja.reyamf.common.model

import android.os.Parcel
import android.os.Parcelable

data class FavApps(
    val packageName: String,
    val userId: Int
): Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(packageName)
        parcel.writeInt(userId)
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<FavApps> {
        override fun createFromParcel(parcel: Parcel) = FavApps(
            packageName = parcel.readString().orEmpty(),
            userId = parcel.readInt()
        )

        override fun newArray(size: Int): Array<FavApps?> = arrayOfNulls(size)
    }
}
