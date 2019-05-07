package com.example.kb1.room;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {WordEn.class}, version= 1, exportSchema = false)
public abstract class WordRoomDatabase extends RoomDatabase {
    public abstract WordDao wordDao();

}

//todo Make singleton?