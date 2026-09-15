package com.groq.voicetyper.ime

/**
 * Navigation states for the Fluence dictation keyboard.
 */
enum class KeyboardPanelMode {
    /** Default voice-first 6-control layout. */
    MAIN,

    /** Secondary 10-key punctuation panel. */
    PUNCTUATION,

    /** Secondary digits and common symbols keypad. */
    NUMBERS
}
