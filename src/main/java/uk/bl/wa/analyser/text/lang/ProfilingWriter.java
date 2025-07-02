package uk.bl.wa.analyser.text.lang;

import java.io.IOException;
import java.io.Writer;

/**
 * Writer that builds a language profile based on all the written content.
 *
 * @since Apache Tika 0.5
 */
public class ProfilingWriter extends Writer {

    private final LanguageProfile profile;

    private char[] buffer = new char[] { '_', 0, 0 };

    private int n = 1;

    public ProfilingWriter(LanguageProfile profile) {
        this.profile = profile;
    }

    public ProfilingWriter() {
        this(new LanguageProfile());
    }

    /**
     * Returns the language profile being built by this writer. Note that
     * the returned profile gets updated whenever new characters are written.
     * Use the {@link #getLanguage()} method to get the language that best
     * matches the current state of the profile.
     *
     * @return language profile
     */
    public LanguageProfile getProfile() {
        return profile;
    }

    /**
     * Returns the language that best matches the current state of the
     * language profile.
     *
     * @return language that best matches the current profile
     */
    public LanguageIdentifier getLanguage() {
        return new LanguageIdentifier(profile);
    }

    @Override
    public void write(char[] cbuf, int off, int len) {
        for (int i = 0; i < len; i++) {
            char c = Character.toLowerCase(cbuf[off + i]);
            if (Character.isLetter(c)) {
                addLetter(c);
            } else {
                addSeparator();
            }
        }
    }

    private void addLetter(char c) {
        System.arraycopy(buffer, 1, buffer, 0, buffer.length - 1);
        buffer[buffer.length - 1] = c;
        n++;
        if (n >= buffer.length) {
            profile.add(new String(buffer));
        }
    }

    private void addSeparator() {
        addLetter('_');
        n = 1;
    }

    @Override
    public void close() throws IOException {
        addSeparator();
    }

    /**
     * Ignored.
     */
    @Override
    public void flush() {
    }

}
