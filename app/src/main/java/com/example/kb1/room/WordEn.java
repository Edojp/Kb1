package com.example.kb1.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "dictionary_en")
public class WordEn {
    @PrimaryKey(autoGenerate = true)
    @NonNull
    @ColumnInfo(name = "id")
    private int mId;

    @NonNull
    @ColumnInfo(name = "word")
    private String mWord;

    @ColumnInfo(name = "count")
    private int mUsage;

    @ColumnInfo(name = "flags")
    private String mFlags;

    public WordEn(@NonNull String word) {
        this.mWord = word;
    }

    public int getId() { return this.mId; }

    public void setId(int uid) { this.mId = uid; }

    public String getWord() {
        return this.mWord;
    }

    public int getUsage() { return this.mUsage; }

    public void setUsage(int usage) { this.mUsage = usage; }

    public String getFlags() {
        return this.mFlags;
    }

    public void setFlags(String flags) {
        this.mFlags = flags;
    }
}

