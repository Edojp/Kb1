package com.example.kb1;

import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

/*
todo list:
-implement db + persistent user word list
-rewrite to ditch KeyboardView, has been deprecated as "convenience class"
-japanese support? (no idea how this is going to work, sounds hard..)
-chinese support? (dear god)
 */

public class IMSvc extends InputMethodService implements KeyboardView.OnKeyboardActionListener {
    private KeyboardView keyboardView;
    private Keyboard keyboard;
    private boolean caps;
    private boolean CANDY_ACTIVE = true;
    private String mCandy1, mCandy2, mCandy3; // todo why are these global?

    /*
    todo should replace KeyboardView implementation with a vanilla layout - has been deprecated
    and has several other drawbacks (like stupid "key code" system)
     */
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
        InputConnection ic = getCurrentInputConnection();

        if (ic != null) {
            ic.commitText(tv.getText().toString(),1);
        }
    }

    public boolean setCandy() {
        /*
        todo this will be most of the work i'm guessing:
        1. look at the text (how much?) in the input field
        2. identify the chars we want to start suggesting candidates on
        3. call dictionary db and return <= 3 suitable candidates
         */
        InputConnection ic = getCurrentInputConnection();
        String inText;
        if (ic != null) {
            /* work backwards from cursor until we find a space
             todo how far back do we go? why did i even pick 10?
             */
            inText = ic.getTextBeforeCursor(10, 0).toString();

            for (int x = inText.length() - 1; x >= 0; x--) {
                if (inText.charAt(x) == ' ') {
                    inText = inText.substring(x);
                    break;
                }
                /* handle non letter case, should not suggest (except apostrophe
                 todo Needs work, what happens if you put ? ! etc at end of word?
                 */
                if (!Character.isLetter(inText.charAt(x)) && inText.charAt(x) != '\'') {
                        return false;
                    }
                }

            /*
            todo why are these global? should probably just pass back a list of candidates
             */
            mCandy1 = inText + "[1]";
            mCandy2 = inText + "[2]";
            mCandy3 = inText + "[3]";

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