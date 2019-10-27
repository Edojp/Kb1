package com.example.kb1.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface WordDao {
    @Insert
    void insert(WordEn word);

    @Query("DELETE from dictionary_en WHERE word = :word")
    int delete(String word);

    // put a limit here so we don't get several hundred word candidate lists
    // whenever users types the first character
    @Query("SELECT * from dictionary_en WHERE word LIKE (:pattern || '%') ORDER BY count DESC LIMIT :max")
    List<WordEn> getWords(String pattern, int max);

    @Query("SELECT * from dictionary_en WHERE word = :pattern")
    WordEn getSingleWord(String pattern);

    @Query("UPDATE dictionary_en SET count = :usage WHERE id = :dbid")
    void setUsage(int dbid, int usage);

    @Query("UPDATE dictionary_en SET followWords = :fw WHERE id = :dbid")
    void setFollowWords(int dbid, String fw);

    @Query("UPDATE dictionary_en SET flags = :flag where id = :dbid")
    void setFlags(int dbid, String flag);

    @Query("SELECT COUNT(word) FROM dictionary_en")
    int getWordCountEn();
}
