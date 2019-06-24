package com.example.kb1;

import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;
import android.widget.Toast;

import androidx.room.Room;

import com.example.kb1.room.WordEn;
import com.example.kb1.room.WordRoomDatabase;

import java.security.InvalidParameterException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;

/*
todo list:
-rewrite to ditch KeyboardView, has been deprecated as "convenience class"
-japanese support? (no idea how this is going to work, sounds hard..)
-chinese support? (dear god)
 */

public class IMSvc extends InputMethodService implements KeyboardView.OnKeyboardActionListener {
    private KeyboardView keyboardView;
    private Keyboard keyboard;
    private boolean caps;
    private boolean CANDY_ACTIVE = true;
    private String mCandy1, mCandy2, mCandy3;
    private static final String DATABASE_NAME = "dic_en_db";
    private WordRoomDatabase mWordDb;

    public void dbInit(){
        mWordDb = Room.databaseBuilder(getApplicationContext(),
                WordRoomDatabase.class, DATABASE_NAME)
                .fallbackToDestructiveMigration()
                .build();

        Toast.makeText(this, "dbInit ok", Toast.LENGTH_LONG).show();
    }

    public void dbWipe() {
        new Thread(() -> mWordDb.clearAllTables()).start();
    }


    private class dbCallWordList implements Callable<List<WordEn>> {
        private String pattern;

        dbCallWordList(String input) { this.pattern = input; }

        @Override
        public List<WordEn> call() throws InvalidParameterException {
            return mWordDb.wordDao().getWords(pattern);
        }
    }


    private class dbCallSingleWord implements Callable<WordEn> {
        private String pattern;

        dbCallSingleWord(String input) { this.pattern = input; }

        @Override
        public WordEn call() throws InvalidParameterException {
            return mWordDb.wordDao().getSingleWord(pattern);
        }
    }


    public List<WordEn> dbGetWords(String word) throws InterruptedException, ExecutionException {
        ExecutorService es = Executors.newSingleThreadExecutor();
        dbCallWordList caller = new dbCallWordList(word);
        Future<List<WordEn>> future = es.submit(caller);

        return future.get();
    }


    public WordEn dbGetSingleWord(String word) throws InterruptedException, ExecutionException {
        ExecutorService es = Executors.newSingleThreadExecutor();
        dbCallSingleWord caller = new dbCallSingleWord(word);
        Future<WordEn> future = es.submit(caller);

        return future.get();
    }


    public void dbAddWord(final String pattern) {
        /*
        Query to see if exists, if not add to database, else
        increment usage and skip
         */
        new Thread(() -> {
            WordEn word = mWordDb.wordDao().getSingleWord(pattern);
            if (word == null) {
                mWordDb.wordDao().insert(new WordEn(pattern));
            } else {
                int usage = word.getUsage();
                mWordDb.wordDao().setUsage(word.getId(), ++usage);
            }
        }).start();
    }


    public View getKeyboardView(String layout) {
        /*
        todo Add some logic to specify alternate mainLayout e.g. dvorak, alphabetical
         */
        int mainLayout = R.layout.layout_qwerty;
        int subLayout = R.layout.layout_sub;

        switch (layout) {
            case "MAIN":
                keyboardView = (KeyboardView) getLayoutInflater().inflate(R.layout.keyboard_view, null);
                keyboard = new Keyboard(this, mainLayout);
                break;
            case "SUB":
                keyboardView = (KeyboardView) getLayoutInflater().inflate(R.layout.keyboard_view, null);
                keyboard = new Keyboard(this, subLayout);
                break;
        }

        keyboardView.setKeyboard(keyboard);
        keyboardView.setOnKeyboardActionListener(this);

        return keyboardView;
    }


    @Override
    public View onCreateInputView() {
        dbInit();
        return getKeyboardView("MAIN");
    }


    public View getCandyView() {
        /* candidate view rebuilt with new candidates on every key press
        todo Probably more efficient to create once and just hide/show/update the text views
        as required than keep recreating the whole view over and over*/

        TextView mCandyText1, mCandyText2, mCandyText3;
        View candyView = getLayoutInflater().inflate(R.layout.layout_candy, null);

        mCandyText1 = candyView.findViewById(R.id.text_candy1);
        mCandyText2 = candyView.findViewById(R.id.text_candy2);
        mCandyText3 = candyView.findViewById(R.id.text_candy3);

        if(!setCandy()) return null;

        mCandyText1.setText(mCandy1);
        mCandyText2.setText(mCandy2);
        mCandyText3.setText(mCandy3);

        return candyView;
    }


    @Override
    public View onCreateCandidatesView() {
        return getCandyView();
    }

    public void onCandyClick(View v){
        /* retrieve the contents of the textview that called this
        and commit to input i.e. user accepted candidate */
        TextView tv = v.findViewById(v.getId());
        String clickedWord = tv.getText().toString();
        InputConnection ic = getCurrentInputConnection();
        CorrectionInfo cob;

        if (ic != null) {
            String inText;
            inText = ic.getTextBeforeCursor(15, 0).toString();

            for (int x = inText.length() - 1; x >= 0; x--) {
                if (!Character.isLetter(inText.charAt(x))) {
                    inText = inText.substring(inText.length() - (inText.length() - x) + 1);
                    break;
                }
            }
            ic.deleteSurroundingText(inText.length(),0);
            ic.commitText(clickedWord + " ",1);
            dbAddWord(clickedWord);
/*
            try {
                WordEn word = dbGetSingleWord(clickedWord);
                Toast.makeText(this,word.getWord() + " usage: " + Integer.toString(word.getUsage()), Toast.LENGTH_LONG).show();
            } catch (InterruptedException e) {
            } catch (ExecutionException e) {
            }
            */
        }
    }


    public boolean setCandy() { //todo why is this boolean, shouldnt this return the candidates?
        /*
        todo this will be most of the work i'm guessing:
        1. look at the text (how much?) in the input field
        2. identify the chars we want to start suggesting candidates on
        3. call dictionary db and return <= 3 suitable candidates
         */
        InputConnection ic = getCurrentInputConnection();

        if (ic != null) {
            String inText;
            /* work backwards from cursor until we find a non letter character
             todo why is this 15? think of something better
             */
            inText = ic.getTextBeforeCursor(15, 0).toString();

            for (int x = inText.length() - 1; x >= 0; x--) {
                if (!Character.isLetter(inText.charAt(x))) {
                    inText = inText.substring(x+1);
                    break;
                }
            }
            /*
            todo Handle non-letter case e.g. hyphen, apostrophe etc.
             */

            try {
                List<WordEn> db_words = dbGetWords(inText);

                if (db_words != null && !db_words.isEmpty()) {
                    // sort returned db_words by usage
                    db_words.sort(Comparator.comparing(WordEn::getUsage).reversed());

                    switch (db_words.size()) {
                        case 1:
                            mCandy1 = "";
                            mCandy2 = db_words.get(0).getWord();
                            mCandy3 = "";
                            break;
                        case 2:
                            mCandy1 = "";
                            mCandy2 = db_words.get(0).getWord();
                            mCandy3 = db_words.get(1).getWord();
                            break;
                        default:
                            mCandy1 = db_words.get(2).getWord();
                            mCandy2 = db_words.get(0).getWord();
                            mCandy3 = db_words.get(1).getWord();
                    }
                    return true;
                }
            } catch (ExecutionException ee) {
                Toast.makeText(this, "thread exception fetching candidates from db",
                        Toast.LENGTH_LONG).show();
            } catch (InterruptedException ie) {
                Toast.makeText(this, "thread exception fetching candidates from db",
                        Toast.LENGTH_LONG).show();
            }

            // if no suggestions returned, show entered word as only candidate
            mCandy1 = "";
            mCandy2 = inText;
            mCandy3 = "";

            return true;
        }
        else {
            return false;
        }
    }


    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        boolean showCandy = false;
        View candyView;

        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            /*
            todo this whole part needs rewriting, should just do a ic.committext
            on the key label contents rather than this stupid ascii-only code system
            and let us get rid of horrible hacks like code 666, 999 etc.
            this will require ditching KeyboardView (has been deprecated anyway)
             */
            switch(primaryCode) {
                case Keyboard.KEYCODE_DELETE:
                    /*
                    grab exactly one char before cursor and check not a surrogate
                    (i.e. not half of a single unicode character). Need isempty check
                    or will crash if the text field is empty when you backspace
                     */
                    CharSequence text = ic.getTextBeforeCursor(1,0);

                    if (text != null && !TextUtils.isEmpty(text)) {
                        char ch = text.charAt(0);
                            if (Character.isSurrogate(ch)) {
                                ic.deleteSurroundingText(2, 0);
                            } else {
                                ic.deleteSurroundingText(1, 0);
                            }
                            showCandy = true;
                        }
                    break;
                case Keyboard.KEYCODE_SHIFT:
                    caps = !caps;
                    keyboard.setShifted(caps);
                    keyboardView.invalidateAllKeys();
                    break;
                case Keyboard.KEYCODE_DONE:
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    break;
                case 32:
                    ic.commitText(" ",1);
                    break;
                case 666:
                    setInputView(getKeyboardView("SUB"));
                    break;
                case 667:
                    setInputView(getKeyboardView("MAIN"));
                    break;
                case 999:
                    ic.commitText("😠", 1);
                    dbWipe();
                    Toast.makeText(this, "db wiped", Toast.LENGTH_LONG).show();
                    break;
                default:
                    char code = (char) primaryCode;
                    if (Character.isLetter(code) && caps) {
                        code = Character.toUpperCase(code);
                    }
                    ic.commitText(String.valueOf(code), 1);
                    showCandy = true;
            }

            if (showCandy && CANDY_ACTIVE) {
                candyView = getCandyView();
                if (candyView != null) {
                    setCandidatesView(candyView);
                    setCandidatesViewShown(true);
                } else {
                    setCandidatesViewShown(false);
                }
            } else {
                setCandidatesViewShown(false);
            }
        }
    }

    /*
    can't imagine needing to use any of these
     */
    @Override
    public void onText(CharSequence text) {
    }


    @Override
    public void swipeLeft() {
    }


    @Override
    public void swipeRight() {
    }


    @Override
    public void swipeDown() {
    }


    @Override
    public void swipeUp() {
    }


    @Override
    public void onPress(int primaryCode) {
    }


    @Override
    public void onRelease(int primaryCode) {
    }
}
