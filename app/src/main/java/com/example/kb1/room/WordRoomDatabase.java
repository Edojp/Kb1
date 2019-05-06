package com.example.kb1.room;

import android.content.Context;
import android.os.AsyncTask;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.security.InvalidParameterException;
import java.util.List;
import java.util.concurrent.Callable;

@Database(entities = {WordEn.class}, version= 1, exportSchema = false)
public abstract class WordRoomDatabase extends RoomDatabase {
    public abstract WordDao wordDao();

}

    /*
    private static volatile WordRoomDatabase INSTANCE;

    static WordRoomDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (WordRoomDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            WordRoomDatabase.class, "dic_en_db")
                 //           .addCallback(sRoomDatabaseCallback)
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    private static class dbGetWords implements Callable<List<WordEn>> {
        List<WordEn> matchedWords;
        private final WordDao mDao;


        public List<WordEn> call() throws InvalidParameterException {
            private final WordDao mDao;


        }
    }


    private static RoomDatabase.Callback sRoomDatabaseCallback =
            new RoomDatabase.Callback() {

                @Override
                public void onOpen(@NonNull SupportSQLiteDatabase db) {
                    super.onOpen(db);
                    new PopulateDbAsync(INSTANCE).execute();
                }
            };

    private static class PopulateDbAsync extends AsyncTask<Void, Void, Void> {
        private final WordDao mDao;

        PopulateDbAsync(WordRoomDatabase db) {mDao = db.wordDao(); }
        @Override
        protected Void doInBackground(final Void... params) {
            WordEn word = new WordEn("Hello");
            mDao.insert(word);
            word = new WordEn("World");
            mDao.insert(word);
            return null;
        }
    }
}
*/