package com.example.kb1.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface WordDao {
    @Insert
    void insert(WordEn word);

    @Update
    void addFlag(WordEn word);

    @Update
    void delFlag(WordEn word);

  //  @Update
  //  void wordIncrementUsage(int usage);

    @Query("SELECT * from dictionary_en WHERE word LIKE (:pattern || '%')")
    List<WordEn> getWords(String pattern);

    @Query("SELECT * from dictionary_en WHERE word = :pattern")
    WordEn getSingleWord(String pattern);

    @Query("UPDATE dictionary_en SET count = :usage WHERE id = :dbid")
    void setUsage(int dbid, int usage);
}
