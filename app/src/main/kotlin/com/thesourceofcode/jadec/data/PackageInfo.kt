/*
 * Show Java - A java/apk decompiler for android
 * Copyright (c) 2018 Niranjan Rajendran
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.thesourceofcode.jadec.data

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import com.thesourceofcode.jadec.utils.Identicon
import com.thesourceofcode.jadec.utils.ktx.getVersion
import com.thesourceofcode.jadec.utils.ktx.isSystemPackage
import com.thesourceofcode.jadec.utils.ktx.jarPackageName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * [PackageInfo] holds information about an apk/jar/dex file in preparation for sending it for
 * decompilation. It also providers helpers to auto-generate an instance from a given [File]
 */
class PackageInfo() : Parcelable {
    var label = ""
    var name = ""
    var version = ""
    var filePath = ""
    var file: File = File("")
    var icon: Drawable? = null
    var type = Type.APK
    var isSystemPackage = false
    var isExternalPackage = false

    constructor(parcel: Parcel) : this() {
        label = parcel.readString()!!
        name = parcel.readString()!!
        version = parcel.readString()!!
        filePath = parcel.readString()!!
        type = Type.values()[parcel.readInt()]
        isSystemPackage = parcel.readInt() == 1
        isExternalPackage = parcel.readInt() == 1
        file = File(filePath)
    }

    constructor(label: String, name: String, version: String, filePath: String, type: Type, isSystemPackage: Boolean = false, isExternalPackage: Boolean = false) : this() {
        this.label = label
        this.name = name
        this.version = version
        this.filePath = filePath
        this.type = type
        this.isSystemPackage = isSystemPackage
        this.isExternalPackage = isExternalPackage
        file = File(filePath)
    }

    constructor(label: String, name: String) : this() {
        this.label = label
        this.name = name
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(label)
        parcel.writeString(name)
        parcel.writeString(version)
        parcel.writeString(filePath)
        parcel.writeInt(type.ordinal)
        parcel.writeInt(if (isSystemPackage) 1 else 0)
        parcel.writeInt(if (isExternalPackage) 1 else 0)
    }

    fun loadIcon(context: Context): Drawable {
        return when(type) {
            Type.APK -> context.packageManager.getPackageArchiveInfo(filePath, 0)
                ?.applicationInfo!!.loadIcon(context.packageManager)
            Type.JAR, Type.DEX ->
                BitmapDrawable(context.resources, Identicon.createFromObject(this.name + this.label))
        }
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun toString(): String {
        return String.format("filePath: %s", filePath)
    }

    enum class Type {
        APK, JAR, DEX
    }

    companion object CREATOR : Parcelable.Creator<PackageInfo> {

        /**
         * Get [PackageInfo] for an apk using the [context] and [android.content.pm.PackageInfo] instance.
         */
        fun fromApkPackageInfo(context: Context, pack: android.content.pm.PackageInfo): PackageInfo {
            return PackageInfo(
                pack.applicationInfo.loadLabel(context.packageManager).toString(),
                pack.packageName,
                getVersion(pack),
                pack.applicationInfo.publicSourceDir,
                Type.APK,
                isSystemPackage(pack)
            )
        }

        /**
         * Get [PackageInfo] for an apk using the [context] and the [file].
         */
        private fun fromApk(context: Context, file: File, isExternalPackage: Boolean = false): PackageInfo? {
            val pack = context.packageManager.getPackageArchiveInfo(file.canonicalPath, 0)
            return PackageInfo(
                pack!!.applicationInfo.loadLabel(context.packageManager).toString(),
                pack.packageName,
                getVersion(pack),
                file.canonicalPath,
                Type.APK,
                isSystemPackage(pack),
                isExternalPackage
            )
        }

        /**
         * Get [PackageInfo] for a jar from the [file].
         */
        private fun fromJar(file: File, type: Type = Type.JAR, isExternalPackage: Boolean = false): PackageInfo? {
            return PackageInfo(
                file.name,
                jarPackageName(file.name),
                (System.currentTimeMillis() / 1000).toString(),
                file.canonicalPath,
                type,
                isExternalPackage = isExternalPackage
            )
        }

        /**
         * Get [PackageInfo] for a dex from the [file].
         */
        private fun fromDex(file: File, isExternalPackage: Boolean = false): PackageInfo? {
            return fromJar(file, Type.DEX, isExternalPackage)
        }


        /**
         * Get [PackageInfo] from a [file].
         */
        fun fromFile(context: Context, file: File): PackageInfo? {
            return try {
                when(file.extension) {
                    "apk" -> fromApk(context, file)
                    "jar" -> fromJar(file)
                    "dex", "odex" -> fromDex(file)
                    else -> null
                }
            } catch (e: NullPointerException) {
                null
            }
        }

        suspend fun fromUri(context: Context, uri: Uri, fileName: String): PackageInfo? {

            val tempFile = File(context.cacheDir, fileName)
            context.contentResolver.openInputStream(uri).use { inputStream ->
                if (inputStream != null) {
                    copy(inputStream, tempFile)
                }
                tempFile
            }
            return try {
                when(fileName!!.substring(fileName.lastIndexOf(".") + 1)) {
                    "apk" -> fromApk(context, tempFile, true)
                    "jar" -> fromJar(tempFile, isExternalPackage = true)
                    "dex", "odex" -> fromDex(tempFile, true)
                    else -> null
                }
            } catch (e: NullPointerException) {
                null
            }
        }
        @Throws(IOException::class)
        suspend fun copy(src: InputStream, dst: File) {
            withContext(Dispatchers.IO) {
                FileOutputStream(dst).use { output ->

                    val buffer = ByteArray(10240)
                    var len: Int = src.read(buffer)
                    var readBytes = len
                    while (len > 0) {
                        yield()
                        output.write(buffer, 0, len)
                        len = src.read(buffer)
                        readBytes += len
                    }
                }
            }
            delay(500)
        }
        override fun createFromParcel(parcel: Parcel): PackageInfo {
            return PackageInfo(parcel)
        }

        override fun newArray(size: Int): Array<PackageInfo?> {
            return arrayOfNulls(size)
        }
    }
}
