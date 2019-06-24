package com.example.kb1.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface WordDao {
    @Insert
    void insert(WordEn word);

    @Query("SELECT * from dictionary_en WHERE word LIKE (:pattern || '%')")
    List<WordEn> getWords(String pattern);

    @Query("SELECT * from dictionary_en WHERE word = :pattern")
    WordEn getSingleWord(String pattern);

    @Query("UPDATE dictionary_en SET count = :usage WHERE id = :dbid")
    void setUsage(int dbid, int usage);

    @Query("UPDATE dictionary_en SET flags = :flag where id = :dbid")
    void setFlags(int dbid, String flag);
}
