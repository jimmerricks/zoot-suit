package com.mickstarify.zooforzotero.ZoteroAPI.Model

import android.os.Parcel
import android.os.Parcelable
import com.google.gson.JsonObject
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import java.util.LinkedList

class Note() : Parcelable {
    lateinit var parent: String
    lateinit var key: String
    lateinit var note: String
    var version: Int = -1
    lateinit var tags: List<String>

    constructor(parcel: Parcel) : this() {
        parent = parcel.readString() ?: throw ExceptionInInitializerError("No Parent Key")
        key = parcel.readString() ?: throw ExceptionInInitializerError("No Key")
        note = parcel.readString() ?: throw ExceptionInInitializerError("No note")
        version = parcel.readInt()
        tags = parcel.createStringArrayList() ?: LinkedList()
    }

    constructor(item: ItemPOJO) : this() {
        parent = item.data["parentItem"] ?: ""
        key = item.data["key"] ?: throw ExceptionInInitializerError("No Key")
        note = item.data["note"] ?: throw ExceptionInInitializerError("No note")
        version = item.version
        tags = item.tags
    }

    constructor(item: Item) : this() {
        parent = item.data["parentItem"] ?: ""
        key = item.data["key"] ?: throw ExceptionInInitializerError("No Key")
        note = item.data["note"] ?: throw ExceptionInInitializerError("No note")
        version = item.getVersion()
        tags = item.getTagList()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(parent)
        parcel.writeString(key)
        parcel.writeString(note)
        parcel.writeInt(version)
        parcel.writeStringList(tags)
    }

    fun getJsonNotePatch(): JsonObject {
        val noteObject = JsonObject()
        noteObject.addProperty("note", note)
        return noteObject
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<Note> {
        override fun createFromParcel(parcel: Parcel): Note {
            return Note(parcel)
        }

        override fun newArray(size: Int): Array<Note?> {
            return arrayOfNulls(size)
        }
    }
}