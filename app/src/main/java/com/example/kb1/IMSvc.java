package com.example.kb1;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputConnection;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.room.Room;

import com.example.kb1.room.WordEn;
import com.example.kb1.room.WordRoomDatabase;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;

/*
todo list:
 -return fuzzy results if no exact match
 -present more candidates if space?
 -track cursor position?
 -rewrite to ditch KeyboardView, has been deprecated as "convenience class"
 -gesture-based input
 -japanese support? (no idea how this is going to work, sounds hard..)
 -chinese support? (in theory this should be easier than jp since no kana
 */

public class IMSvc extends InputMethodService implements KeyboardView.OnKeyboardActionListener {
    private KeyboardView mKeyboardView;
    private View mCandyView;
    private Keyboard mKeyboard;
    private boolean mCapsEnabled = false;
    private boolean mCapsLock = false;
    private static final String DB_TABLE_EN = "dic_en_db";
    private WordRoomDatabase mWordDb;
    private final int MAX_SEEK_CHARS = 15;
    private int mCursorPos;
    private TextView mCandyText1, mCandyText2, mCandyText3;
    private WordEn mLastWord;

    //todo fix this - automatically open relevant settings screen if no access
    //final boolean overlayEnabled = Settings.canDrawOverlays(getApplicationContext(MainActivity.this));

    @Override
    public View onCreateInputView() {
        //if (!overlayEnabled) {
        //    openOverlaySettings();
        //}
        dbInit();
        return getKeyboardView("MAIN");
    }


    private void openOverlaySettings() {
        Log.i("overlay", "got here");
        final Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                ,Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.e("MainActivity", e.getMessage());
        }
    }


    // stops the candidate bar overlapping the app (because ??)
    @Override
    public void onComputeInsets(InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (!isFullscreenMode()) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets;
        }
    }


    public View getKeyboardView(String layout) {
        /*
        todo Add some logic to specify alternate mainLayout e.g. dvorak, alphabetical
         */
        int mainLayout = R.layout.layout_qwerty;
        int subLayout = R.layout.layout_sub;

        switch (layout) {
            case "MAIN":
                mKeyboardView = (KeyboardView) getLayoutInflater().inflate(R.layout.keyboard_view, null);
                mKeyboard = new Keyboard(this, mainLayout);
                break;
            case "SUB":
                mKeyboardView = (KeyboardView) getLayoutInflater().inflate(R.layout.keyboard_view, null);
                mKeyboard = new Keyboard(this, subLayout);
                break;
        }

        mKeyboardView.setKeyboard(mKeyboard);
        mKeyboardView.setOnKeyboardActionListener(this);
        initCandyView(getCandy());
        setCandidatesViewShown(true);

        return mKeyboardView;
    }


    public boolean importWordlist() {
        Log.d("inportWordList", "attempting to import word list from xml");
        InputStream stream = null;
        List<String> words = new ArrayList<>();

        WordListXmlParser mWordListXmlParser = new WordListXmlParser();
        stream = getResources().openRawResource(R.raw.wordlist_en);

        try {
            words = mWordListXmlParser.parse(stream);
        } catch (XmlPullParserException | IOException e) {
            Log.e("importWordList", e.toString() +  e.getMessage());
        }

        if (!words.isEmpty()) {
            Log.i("importWordList", "importing " + words.size() + " entries");
            for (String pattern : words) {
                dbAddWord(new WordEn(pattern));
            }
            return true;
        }

        return false;
    }


    public void dbInit(){
        mWordDb = Room.databaseBuilder(getApplicationContext(),
                WordRoomDatabase.class, DB_TABLE_EN)
                .fallbackToDestructiveMigration()
                .build();

        new Thread(() -> {
            int word_count = mWordDb.wordDao().getWordCountEn();

            Log.i("dbinit", "word count is " + word_count);
            // only import wordlist if not done already
            if (word_count < 1000) {
                if(!importWordlist()) {
                    Log.e("dbInit", "some issue importing word list");
                }
            }
        }).start();

        Toast.makeText(this, "dbInit ok!", Toast.LENGTH_LONG).show();
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


    public void dbAddWord(WordEn word) {
        new Thread(() -> {
            mWordDb.wordDao().insert(word);
        }).start();

    }


    public void dbIncrementWordUsage(WordEn word) {
        new Thread (() -> {
            int usage = word.getUsage();
            mWordDb.wordDao().setUsage(word.getId(), ++usage);
        }).start();
    }


    // present confirmation alert and delete word from db
    public void dbDelWord(final String pattern) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setTitle(R.string.alert_delete_title);
        builder.setMessage(getResources().getString(R.string.alert_delete_message, pattern));

        builder.setPositiveButton(R.string.alert_button_delete, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                new Thread(() -> {
                    int result = mWordDb.wordDao().delete(pattern);
                    if (result < 1) {
                        Log.w("dbdDelWord", "tried to delete word that doesn't exist: " + pattern);
                    }

                }).start();
                // refresh view
                initCandyView(getCandy());
            }
        });

        builder.setNegativeButton(R.string.alert_button_cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        AlertDialog ad = builder.create();
        ad.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        ad.show();
    }


    public void dbUpdateFollowWords(final WordEn word, final String pattern) {
        // update db record for word to list pattern as a follow word
        // up to a maximum of 3, subsequent patterns are rotated out

        // follow words should not contain self
        if (word.getWord().equals(pattern)) {
            Log.d("dbUpdateFollowWords()", "pattern same as word, skipping: " + pattern);
            return;
        }

        new Thread(() -> {
            String out = pattern;
            String fw = word.getFollowWords();

            if (fw != null) {
                List<String> candidates = parseFollowWords(fw);

                // follow words should not contain duplicates
                if (candidates.contains(pattern)) {
                    return;
                }

                // enforce max 3 candidates
                int shift = 0;
                if (candidates.size() > 2) {
                    shift = candidates.size() - 2;
                }

                for (int x = 0; x < candidates.size() - shift; x++) {
                    out += "," + candidates.get(x);
                }
            }

            Log.d("dbUpdateFollowWords", "db update for word: " + word.getWord()
                    + " fw: " + out);

            mWordDb.wordDao().setFollowWords(word.getId(), out);
        }).start();
    }


    public List<String> parseFollowWords(String fw) {
        // create a list of usable candidates from input string stored in db
        // fw format should be: word1,word2,word3

        List<String> candidates = new ArrayList<>();
        int start = 0;

        for (int x = 0; x < fw.length(); x++) {
            if (fw.charAt(x) == ',') {
                String ss = fw.substring(start, x);
                if (!ss.isEmpty()) {
                    candidates.add(ss);
                }
                start = x + 1;
            }
        }

        // don't forget to add last substring!
        candidates.add(fw.substring(start));

        return candidates;
    }


    // take the text from a user elected candidate, commit to text field,
    // create word if doesn't exist, updates usage if does exist,
    // prepares predicted next words aka "follow words"
    public void commitClickedWord(String text) {
        InputConnection ic = getCurrentInputConnection();

        if (ic != null) {
            String inText;
            inText = ic.getTextBeforeCursor(MAX_SEEK_CHARS, 0).toString();

            // work backwards from the cursor until we find something that
            // isn't a letter/apostrophe/hyphen (usually a space)
            for (int x = inText.length() - 1; x >= 0; x--) {
                if (!Character.isLetter(inText.charAt(x))) {
                    if (inText.charAt(x) == '\'' || inText.charAt(x) == '-') {
                        continue;
                    }

                    inText = inText.substring(inText.length() - (inText.length() - x) + 1);
                    break;
                }
            }
            //TODO there's a probably a better of way of doing this...
            // though does come with added bonus of stripping probably erroneous
            // second upper case char e.g. "WOrd" -> "Word" and keeps capitalized
            // words out of the dictionary database

            // need a check here for scenario of accepting the candidate when
            // no other text is in the field
            int shift =  0;
            if (!inText.isEmpty()) {
                // delete up to (but not including) first character
                ic.deleteSurroundingText(inText.length() - 1, 0);
                shift = 1;
            }
            // commit from 2nd character onwards if replacing existing word
            ic.commitText(text.substring(shift) + " ", 1);

            // TODO fix bug where all 3 candidates are same word

            // blank out the candidate bar, it will repopulate if there are
            // valid follow words
            initCandyView(new ArrayList<>());

            try {
                // attempt to get exact match from db and create new
                // wordEn object if no hits
                WordEn word = dbGetSingleWord(text.toLowerCase());

                if (word == null) {
                    word = new WordEn(text.toLowerCase());
                    dbAddWord(word);
                } else {
                    dbIncrementWordUsage(word);

                    // see if word object has a followWords field and parse into
                    // candidates if so
                    if (word.getFollowWords() != null) {
                        List<String> candidates = parseFollowWords(word.getFollowWords());
                        initCandyView(candidates);
                    }
                }

                // add current word into the previous word's followWords list
                // spins off a new thread but ok as we don't need in real time
                if (mLastWord != null) {
                    dbUpdateFollowWords(mLastWord, word.getWord());
                }

                // finally, current word now registed as the last word committed
                mLastWord = word;
                Log.d("commitClickedWord", "mLastWord is now: " + word.getWord());

            } catch (InterruptedException | ExecutionException e) {
                Log.e("commitclickedword", e.getMessage());
            }
        }
    }


    public List<String> getCandy() {
        List<String> candidates = new ArrayList<>();

        InputConnection ic = getCurrentInputConnection();

        if (ic != null) {
            String inText;

            // work backwards from cursor until we find a non letter character
            inText = ic.getTextBeforeCursor(MAX_SEEK_CHARS, 0).toString();

            for (int x = inText.length() - 1; x >= 0; x--) {
                if (!Character.isLetter(inText.charAt(x))) {
                    if (inText.charAt(x) == '\'' || inText.charAt(x) == '-') {
                        continue;
                    }

                    inText = inText.substring(x+1);
                    break;
                }
            }

            try {
                // query should only return a max of 3
                List<WordEn> words = dbGetWords(inText);

                for (WordEn word : words) {
                    candidates.add(word.getWord());
                }

                //TODO how do we present a new word (for adding) that's a substring
                // of valid > 3 candidates? e.g. "goo"

            } catch (ExecutionException | InterruptedException e) {
                Log.e("getCandy",  e.getMessage());
            }

            // return intext as sole candidate if no matches in db
            if (candidates.size() == 0) {
                candidates.add(inText);
            }
        }

        return candidates;
    }


    public void initCandyView(List<String> candidates) {
        // create the view if it doesn't exist
        if (mCandyView == null) {
            mCandyView = getLayoutInflater().inflate(R.layout.layout_candy, null);

            mCandyText1 = mCandyView.findViewById(R.id.text_candy1);
            mCandyText2 = mCandyView.findViewById(R.id.text_candy2);
            mCandyText3 = mCandyView.findViewById(R.id.text_candy3);

            mCandyText1.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // TODO do we need this check? will this ever happen?
                    if (!mCandyText1.getText().toString().isEmpty()) {
                        commitClickedWord(mCandyText1.getText().toString());
                    }
                }
            });

            mCandyText1.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (!mCandyText1.getText().toString().isEmpty()) {
                        dbDelWord(mCandyText1.getText().toString());
                    }
                    return true;
                }
            });

            mCandyText2.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!mCandyText2.getText().toString().isEmpty()) {
                        commitClickedWord(mCandyText2.getText().toString());
                    }
                }
            });

            // TODO what if user long presses a new word (candidate for dictionary add)?
            mCandyText2.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (!mCandyText2.getText().toString().isEmpty()) {
                        dbDelWord(mCandyText2.getText().toString());
                    }
                    return true;
                }
            });

            mCandyText3.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!mCandyText3.getText().toString().isEmpty()) {
                        commitClickedWord(mCandyText3.getText().toString());
                    }
                }
            });

            mCandyText3.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (!mCandyText3.getText().toString().isEmpty()) {
                        dbDelWord(mCandyText3.getText().toString());
                    }
                    return true;
                }
            });
        }

        // update textviews with latest candidates depending on
        // how many candidates were passed
        switch (candidates.size()) {
            case 1:
                mCandyText1.setText("");
                mCandyText2.setText(candidates.get(0));
                mCandyText3.setText("");
                break;
            case 2:
                mCandyText1.setText("");
                mCandyText2.setText(candidates.get(0));
                mCandyText3.setText(candidates.get(1));
                break;
            case 3:
                mCandyText1.setText(candidates.get(2));
                mCandyText2.setText(candidates.get(0));
                mCandyText3.setText(candidates.get(1));
                break;
            default:
                mCandyText1.setText("");
                mCandyText2.setText("");
                mCandyText3.setText("");
        }

        if (mCandyText1.getText().equals("")) {
            mCandyText1.setBackgroundResource(R.color.colorPrimaryDark);
        } else {
            mCandyText1.setBackgroundResource(R.drawable.rounded_corners);
        }

        if (mCandyText2.getText().equals("")) {
            mCandyText2.setBackgroundResource(R.color.colorPrimaryDark);
        } else {
            mCandyText2.setBackgroundResource(R.drawable.rounded_corners);
        }

        if (mCandyText3.getText().equals("")) {
            mCandyText3.setBackgroundResource(R.color.colorPrimaryDark);
        } else {
            mCandyText3.setBackgroundResource(R.drawable.rounded_corners);
        }
    }


    @Override
    public View onCreateCandidatesView() {
        initCandyView(getCandy());
        return mCandyView;
    }


    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            /*
            todo this whole part needs rewriting, should just do a ic.committext
             on the key label contents rather than this stupid ascii-only code system
             and let us get rid of horrible hacks like code 666, 999 etc.
             this will require ditching KeyboardView (has been deprecated anyway)
             */

            // do we ever want this to be false?
            setCandidatesViewShown(true);

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
                        }
                    break;
                case Keyboard.KEYCODE_SHIFT:
                    /*
                    caps 1 letter only on single press, locks on
                    second press, back to lowercase on third press
                     */
                    if (mCapsEnabled) {
                        if (mCapsLock) { // caps on, lock on
                            mCapsEnabled = false;
                            mCapsLock = false;
                        } else { // caps on, lock off
                            mCapsLock = true;
                        }
                    } else { // caps off = lock off
                        mCapsEnabled = true;
                        // caps off, lock on should never happen
                    }

                    mKeyboard.setShifted(mCapsEnabled);
                    mKeyboardView.invalidateAllKeys();
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
                case 46:
                case 45:
                case 44:
                case 43:
                case 33:
                case 63:
                    // remove leading space if exists, required for period, comma etc
                    if (ic.getTextBeforeCursor(1,0).equals(" ")) {
                        ic.deleteSurroundingText(1, 0);
                    }
                    // break deliberately left out
                default:
                    char code = (char) primaryCode;
                    if (Character.isLetter(code) && mCapsEnabled) {
                        code = Character.toUpperCase(code);
                    }
                    ic.commitText(String.valueOf(code), 1);

                    // only want caps on first character (unless locked)
                    if (!mCapsLock) {
                        mCapsEnabled = false;
                        mKeyboard.setShifted(mCapsEnabled);
                        mKeyboardView.invalidateAllKeys();
                    }
            }
            // should suggest a candidate from examining the input field
            // in all cases except clicking something in candidate bar
            initCandyView(getCandy());
        }
    }

    //can't imagine needing to use any of these
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
