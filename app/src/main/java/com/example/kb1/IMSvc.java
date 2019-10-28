package com.example.kb1;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
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
 -track cursor position?
 -rewrite to ditch KeyboardView, has been deprecated
 -gesture-based input
 -japanese support? (no idea how this is going to work, sounds hard..)
 -chinese support? (in theory this should be easier than jp since no kana
 -korean support? (relatively..) simple alphabet, should be the easiest of cjk
 */

public class IMSvc extends InputMethodService implements KeyboardView.OnKeyboardActionListener {
    private KeyboardView mKeyboardView;
    private View mCandyView;
    private Keyboard mKeyboard;
    private WordEn mLastWord;
    private boolean mCapsEnabled = false;
    private boolean mCapsLock = false;
    private List<TextView> mCandyTextViews = new ArrayList<>();

    private static final String DB_TABLE_EN = "dic_en_db";
    private WordRoomDatabase mWordDb;

    private final int MAX_SEEK_CHARS = 15;
    private final int MAX_CANDIDATES = 6;
    private final int FONT_SIZE_CANDIDATE = 24;

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
                mKeyboardView = (KeyboardView) getLayoutInflater()
                        .inflate(R.layout.keyboard_view, null);
                mKeyboard = new Keyboard(this, mainLayout);
                break;
            case "SUB":
                mKeyboardView = (KeyboardView) getLayoutInflater()
                        .inflate(R.layout.keyboard_view, null);
                mKeyboard = new Keyboard(this, subLayout);
                break;
        }

        mKeyboardView.setKeyboard(mKeyboard);
        mKeyboardView.setOnKeyboardActionListener(this);
        setCandyView(getCandy());
        setCandidatesViewShown(true);

        return mKeyboardView;
    }


    public boolean importWordlist() {
        Log.d("inportWordList", "attempting to import word list from xml");
        List<String> words = new ArrayList<>();

        WordListXmlParser mWordListXmlParser = new WordListXmlParser();
        InputStream stream = getResources().openRawResource(R.raw.wordlist_en);

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
            //TODO come up with something more clever...
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
            // this will technically give us 7 (6 + input) but we remove the dup later
            return mWordDb.wordDao().getWords(pattern, MAX_CANDIDATES);
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


    // present confirmation prompt and delete word from db
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
                        Log.w("dbdDelWord", "tried to delete word that doesn't exist: "
                                + pattern);
                    }


                }).start();

                // refresh view after deletion
                //TODO this doesn't work, deleted word still shows in the list
                // probably because it still exists at the point this is called
               // setCandyView(getCandy());
            }
        });

        builder.setNegativeButton(R.string.alert_button_cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        AlertDialog ad = builder.create();
        try {
            ad.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            ad.show();
        }
        catch (NullPointerException e) {
            Log.d("dbDelWord", "failed to create confirmation pop-up:" + e.getMessage());
        }
    }


    public void dbUpdateFollowWords(final WordEn word, final String pattern) {
        // update db record for word to list pattern as a follow word
        // up to a maximum of MAX_CANDIDATES, subsequent patterns are rotated out
        new Thread(() -> {
            String out = pattern;
            String fw = word.getFollowWords();

            if (fw != null) {
                List<String> follow_words = parseFollowWords(fw);

                // remove the word if it already exists so it can be
                // reinserted at the top of the stack
                follow_words.remove(pattern);

                // set shift here so we skip the last entry if at max
                int shift = follow_words.size() < MAX_CANDIDATES ? 0 : 1;

                // start by adding the new word
                StringBuilder sb = new StringBuilder();
                sb.append(out);

                // read the previous follow words (except least recently used 1)
                for (int x = 0; x < follow_words.size() - shift; x++) {
                    sb.append(",");
                    sb.append(follow_words.get(x));
                }

                out = sb.toString();
            }

            Log.d("dbUpdateFollowWords", "db update for word: " + word.getWord()
                    + " fw: " + out);

            mWordDb.wordDao().setFollowWords(word.getId(), out);
        }).start();
    }


    public List<String> parseFollowWords(String fw) {
        // create a list of usable candidates from input string stored in db
        // fw format should be: word1,word2,etc

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


    public List<String> getFuzzyWords (String pattern) {
        try {
            List<WordEn> fuzzyWords = dbGetWords(pattern);

            if (fuzzyWords.size() == 0) {
                for (int x = 1; x < pattern.length(); x++){
                    fuzzyWords = dbGetWords(pattern.substring(pattern.length() - x));
                    if (fuzzyWords.size() < MAX_CANDIDATES) {
                        //TODO
                    }
                }
            }

        } catch (InterruptedException | ExecutionException e) {
            Log.e("checkCandySpace", e.getMessage());
        }

        // return blank list if all else fails
        return (new ArrayList<>());
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

            // blank out the candidate bar, it will repopulate if there are
            // valid follow words
            setCandyView(new ArrayList<>());

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
                        setCandyView(candidates);
                    }
                }

                // add current word into the previous word's followWords list
                // spins off a new thread but ok as we don't need in real time
                if (mLastWord != null) {
                    dbUpdateFollowWords(mLastWord, word.getWord());
                }

                // finally, current word now registered as the last word committed
                mLastWord = word;
                Log.d("commitClickedWord", "mLastWord is now: " + word.getWord());

            } catch (InterruptedException | ExecutionException e) {
                Log.e("commitclickedword", e.getMessage());
            }
        }
    }


    public int dpToPx(int px) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                px, getResources().getDisplayMetrics());
    }


    // create a textview, then measure and check if we have room to add it
    // returns the new total width of the candidates or -1 if no room
    public int addCandidateToCandyViewList(String text, int current_width, int screen_width) {
        if (text.isEmpty() || mCandyView == null) {
            return -1;
        }

        // first we create the view and then measure it
        TextView tv = new TextView(this);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dpToPx(4), dpToPx(3), dpToPx(4), dpToPx(2));
        tv.setLayoutParams(params);

        tv.setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5));
        tv.setBackgroundResource(R.drawable.rounded_corners);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_SIZE_CANDIDATE);
        tv.setTextColor(ContextCompat.getColor(this, R.color.colorLightText));

        tv.setOnClickListener((textView) -> commitClickedWord(text));
        // TODO what if user long presses a new word (candidate for dictionary add)?
        tv.setOnLongClickListener((textView) -> {
                dbDelWord(text);
                return true;
        });

        tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int tv_width = tv.getMeasuredWidth();

        // check if adding the word will take us over x% of screen width
        // and cause wrapping (leave some % for padding, margins etc.)
        if (current_width + tv_width <= screen_width * 0.88) {
            current_width += tv_width;

            // store the checked view into the candidate view list
            mCandyTextViews.add(tv);

            // return the new total width
            return current_width;
        }

        // return -1 if no room to add
        return -1;
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

            // add the user input as the first candidate
            candidates.add(inText);

            try {
                List<WordEn> words = dbGetWords(inText);

                for (WordEn word : words) {
                    // prevent showing same word twice if intext is same as a word in db
                    if (!word.getWord().equals(inText)) {
                        candidates.add(word.getWord());
                    }
                }
            }
            catch (ExecutionException | InterruptedException e) {
                Log.e("getCandy",  e.getMessage());
            }
        }

        return candidates;
    }


    public void clearCandidates() {
        if (mCandyView != null) {
            for (TextView tv : mCandyTextViews) {
                ((LinearLayout) mCandyView).removeView(tv);
            }

            // clear list when done
            mCandyTextViews.clear();
        }
    }


    public void setCandyView(List<String> candidates) {
       // create the view if it doesn't exist
       if (mCandyView == null) {
           mCandyView = new LinearLayout(this);
           // these are ignored, looks like candidate view is always match_parent x wrap_contents
          // LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            //       ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(80));
           //mCandyView.setLayoutParams(params);

           // if this is not set, candidate bar will disappear when no candidates to present
           mCandyView.setMinimumHeight(dpToPx(40));

           ((LinearLayout) mCandyView).setOrientation(LinearLayout.HORIZONTAL);
           ((LinearLayout) mCandyView).setGravity(Gravity.CENTER);
           mCandyView.setBackgroundColor(ContextCompat.getColor(this,
                   R.color.colorPrimaryDark));
       }

       // remove any existing candidate textviews
       //TODO OR we could just rebuild the candidate view from scratch every time
       // and we wouldn't need to do this, which is more efficient?
       clearCandidates();

       // generate textview list. add up the total width of all the candidates
       // to the candidate bar until we run out of space
       int total_width = 0;
       int screen_width = Resources.getSystem().getDisplayMetrics().widthPixels;

       for (String word : candidates) {
           // stop if returns -1 (i.e. out of room)
           total_width = addCandidateToCandyViewList(word, total_width, screen_width);
           if (total_width == -1) {
               break;
           }
       }

       // candidates should appear in view the in the following order
       // current entry in center then fan out e.g.: 5 3 1 0 2 4
       for (int x = 0; x < mCandyTextViews.size(); x++) {
           if (x % 2 == 0) {
               // add to the right
               ((LinearLayout) mCandyView).addView(mCandyTextViews.get(x));
           }
           else {
               // add to the left
               ((LinearLayout) mCandyView).addView(mCandyTextViews.get(x), 0);
           }
       }

       //tell IM service that the candidate view has changed
       setCandidatesView(mCandyView);
    }


    @Override
    public View onCreateCandidatesView() {
        setCandyView(getCandy());
        return mCandyView;
    }


    @Override
    public void onWindowShown() {
        super.onWindowShown();
        // typically want to start with a capital letter
        determineCapsState();
    }


    @Override
    public void setCandidatesView(View view) {
        super.setCandidatesView(view);
    }


    // look at previous text and determine if we should
    // enable caps mode for the user or not
    // TODO any other cases?
    public void determineCapsState() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            boolean caps = false;
            CharSequence char1 = ic.getTextBeforeCursor(1, 0);
            CharSequence char2 = ic.getTextBeforeCursor(2, 0);

            // couldn't get one character, must be at start of edittext
            if(TextUtils.isEmpty(char1)) {
                caps = true;
            }

            // period/excl/question mark + space typically means new sentence
            if(!TextUtils.isEmpty(char2)) {
                if (char2.equals(". ") || char2.equals("? ") || char2.equals("! ")) {
                    caps = true;
                }
            }

            if (caps) {
                mCapsEnabled = true;
                mKeyboardView.setShifted(mCapsEnabled);
                mKeyboardView.invalidateAllKeys();
            }
        }
    }


    /*
    todo this whole part needs rewriting, should just do a ic.committext
     on the key label contents rather than this stupid ascii-only code system
     and let us get rid of horrible hacks like code 666, 999 etc.
     */
    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {

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
                   // dbWipe();
                   // Toast.makeText(this, "db wiped", Toast.LENGTH_LONG).show();
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
            setCandyView(getCandy());
            determineCapsState();
        }
    }

    // remaining mandatory overrides
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
