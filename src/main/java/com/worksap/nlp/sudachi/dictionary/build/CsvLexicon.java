/*
 * Copyright (c) 2021 Works Applications Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.worksap.nlp.sudachi.dictionary.build;

import com.worksap.nlp.sudachi.WordId;
import com.worksap.nlp.sudachi.dictionary.POS;
import com.worksap.nlp.sudachi.dictionary.WordInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CsvLexicon implements WriteDictionary {
    static final int ARRAY_MAX_LENGTH = Byte.MAX_VALUE;
    static final int MIN_REQUIRED_NUMBER_OF_COLUMNS = 18;
    static final Pattern unicodeLiteral = Pattern.compile("\\\\u([0-9a-fA-F]{4}|\\{[0-9a-fA-F]+})");
    private static final Pattern PATTERN_ID = Pattern.compile("U?\\d+");
    private final Parameters parameters = new Parameters();
    private final POSTable posTable;
    private final List<WordEntry> entries = new ArrayList<>();
    private WordIdResolver widResolver = new WordLookup.Noop();

    public CsvLexicon(POSTable pos) {
        posTable = pos;
    }

    public void setResolver(WordIdResolver widResolver) {
        this.widResolver = widResolver;
    }

    /**
     * Resolve unicode escape sequences in the string
     * <p>
     * Sequences are defined to be \\u0000-\\uFFFF: exactly four hexadecimal
     * characters preceded by \\u \\u{...}: a correct unicode character inside
     * brackets
     *
     * @param text
     *            to to resolve sequences
     * @return string with unicode escapes resolved
     */
    public static String unescape(String text) {
        Matcher m = unicodeLiteral.matcher(text);
        if (!m.find()) {
            return text;
        }

        StringBuffer sb = new StringBuffer();
        m.reset();
        while (m.find()) {
            String u = m.group(1);
            if (u.startsWith("{")) {
                u = u.substring(1, u.length() - 1);
            }
            m.appendReplacement(sb, new String(Character.toChars(Integer.parseInt(u, 16))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public List<WordEntry> getEntries() {
        return entries;
    }

    WordEntry parseLine(List<String> cols) {
        if (cols.size() < MIN_REQUIRED_NUMBER_OF_COLUMNS) {
            throw new IllegalArgumentException("invalid format");
        }
        for (int i = 0; i < 15; i++) {
            cols.set(i, unescape(cols.get(i)));
        }

        if (cols.get(0).getBytes(StandardCharsets.UTF_8).length > DicBuffer.MAX_STRING
                || !DicBuffer.isValidLength(cols.get(4)) || !DicBuffer.isValidLength(cols.get(11))
                || !DicBuffer.isValidLength(cols.get(12))) {
            throw new IllegalArgumentException("string is too long");
        }

        if (cols.get(0).isEmpty()) {
            throw new IllegalArgumentException("headword is empty");
        }

        WordEntry entry = new WordEntry();

        // headword for trie
        if (!cols.get(1).equals("-1")) {
            entry.headword = cols.get(0);
        }

        // left-id, right-id, cost
        parameters.add(Short.parseShort(cols.get(1)), Short.parseShort(cols.get(2)), Short.parseShort(cols.get(3)));

        // part of speech
        POS pos = new POS(cols.get(5), cols.get(6), cols.get(7), cols.get(8), cols.get(9), cols.get(10));
        short posId = posTable.getId(pos);

        entry.dictionaryFormString = cols.get(13);
        entry.aUnitSplitString = cols.get(15);
        entry.bUnitSplitString = cols.get(16);
        entry.wordStructureString = cols.get(17);
        checkSplitInfoFormat(entry.aUnitSplitString);
        checkSplitInfoFormat(entry.bUnitSplitString);
        checkSplitInfoFormat(entry.wordStructureString);
        if (cols.get(14).equals("A") && (!entry.aUnitSplitString.equals("*") || !entry.bUnitSplitString.equals("*"))) {
            throw new IllegalArgumentException("invalid splitting");
        }

        int[] synonymGids = new int[0];
        if (cols.size() > 18) {
            synonymGids = parseSynonymGids(cols.get(18));
        }

        entry.wordInfo = new WordInfo(cols.get(4), // headword
                (short) cols.get(0).getBytes(StandardCharsets.UTF_8).length, posId, cols.get(12), // normalizedForm
                -1, "", // dictioanyForm (dummy)
                cols.get(11), // readingForm
                null, null, null, synonymGids);

        return entry;
    }

    /**
     * Parse dictionary_form string in lexicon as referring word id.
     * 
     * Allow id (line no) or triple format. Forbid to refer word outside this
     * lexicon (user word cannot use system word as dictionary form).
     */
    int parseDictionaryForm(String str) {
        if (str.equals("*")) {
            return -1;
        }
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            // noop
        }
        int wordId = wordToId(str);
        if (wordId < 0) {
            throw new IllegalArgumentException("couldn't find " + str + " in the dictionaries");
        }
        if (!wordRefMatches(str, wordId)) {
            throw new IllegalArgumentException("dictionary form must exist in the same lexicon");
        }
        return wordId;
    }

    /**
     * Check if wordRef matches to the word in this lexicon.
     */
    boolean wordRefMatches(String ref, int wordId) {
        if (wordId < 0 || entries.size() <= wordId) {
            return false;
        }
        WordEntry entry = entries.get(wordId);

        String[] cols = ref.split(",", 8);
        String headword = unescape(cols[0]);
        POS pos = new POS(Arrays.copyOfRange(cols, 1, 7));
        short posId = posTable.getId(pos);
        String reading = unescape(cols[7]);

        return headword.equals(entry.headword) && posId == entry.wordInfo.getPOSId()
                && reading.equals(entry.wordInfo.getReadingForm());
    }

    int[] parseSynonymGids(String str) {
        if (str.equals("*")) {
            return new int[0];
        }
        String[] ids = str.split("/");
        if (ids.length > ARRAY_MAX_LENGTH) {
            throw new IllegalArgumentException("too many units");
        }
        int[] ret = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            ret[i] = Integer.parseInt(ids[i]);
        }
        return ret;
    }

    int wordToId(String text) {
        String[] cols = text.split(",", 8);
        if (cols.length < 8) {
            throw new IllegalArgumentException("too few columns");
        }
        String headword = unescape(cols[0]);
        POS pos = new POS(Arrays.copyOfRange(cols, 1, 7));
        short posId = posTable.getId(pos);
        String reading = unescape(cols[7]);
        return widResolver.lookup(headword, posId, reading);
    }

    void checkSplitInfoFormat(String info) {
        if (info.chars().filter(i -> i == '/').count() + 1 > ARRAY_MAX_LENGTH) {
            throw new IllegalArgumentException("too many units");
        }
    }

    boolean isId(String text) {
        return PATTERN_ID.matcher(text).matches();
    }

    int[] parseSplitInfo(String info) {
        if (info.equals("*")) {
            return new int[0];
        }
        String[] words = info.split("/");
        if (words.length > ARRAY_MAX_LENGTH) {
            throw new IllegalArgumentException("too many units");
        }
        int[] ret = new int[words.length];
        for (int i = 0; i < words.length; i++) {
            String ref = words[i];
            if (isId(ref)) {
                ret[i] = parseId(ref);
            } else {
                ret[i] = wordToId(ref);
                if (ret[i] < 0) {
                    throw new IllegalArgumentException("couldn't find " + ref + " in the dictionaries");
                }
            }
        }
        return ret;
    }

    int parseId(String text) {
        int id = 0;
        if (text.startsWith("U")) {
            id = Integer.parseInt(text.substring(1));
            if (widResolver.isUser()) {
                id = WordId.make(1, id);
            }
        } else {
            id = Integer.parseInt(text);
        }
        widResolver.validate(id);
        return id;
    }

    @Override
    public void writeTo(ModelOutput output) throws IOException {
        // write number of entries
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(entries.size());
        buf.flip();
        output.write(buf);

        parameters.writeTo(output);

        int offsetsSize = 4 * entries.size();
        DicBuffer offsets = new DicBuffer(offsetsSize);
        long offsetsPosition = output.position();
        // make a hole for
        output.position(offsetsPosition + offsetsSize);

        output.withPart("word entries", () -> {
            DicBuffer buffer = new DicBuffer(128 * 1024);
            int offset = (int) output.position();
            int numEntries = entries.size();
            for (int i = 0; i < numEntries; ++i) {
                WordEntry entry = entries.get(i);
                if (buffer.wontFit(16 * 1024)) {
                    offset += buffer.consume(output::write);
                }
                offsets.putInt(offset + buffer.position());

                WordInfo wi = entry.wordInfo;
                buffer.put(wi.getSurface());
                buffer.putLength(wi.getLength());
                buffer.putShort(wi.getPOSId());
                buffer.putEmptyIfEqual(wi.getNormalizedForm(), wi.getSurface());
                buffer.putInt(parseDictionaryForm(entry.dictionaryFormString));
                buffer.putEmptyIfEqual(wi.getReadingForm(), wi.getSurface());
                buffer.putInts(parseSplitInfo(entry.aUnitSplitString));
                buffer.putInts(parseSplitInfo(entry.bUnitSplitString));
                buffer.putInts(parseSplitInfo(entry.wordStructureString));
                buffer.putInts(wi.getSynonymGoupIds());
                output.progress(i, numEntries);
            }

            buffer.consume(output::write);
        });

        long pos = output.position();
        output.position(offsetsPosition);
        output.withPart("WordInfo offsets", () -> offsets.consume(output::write));
        output.position(pos);
    }

    public int addEntry(WordEntry e) {
        int id = entries.size();
        entries.add(e);
        return id;
    }

    public void setLimits(int left, int right) {
        parameters.setLimits(left, right);
    }

    public static class WordEntry {
        String headword;
        WordInfo wordInfo;
        String dictionaryFormString;
        String aUnitSplitString;
        String bUnitSplitString;
        String wordStructureString;
    }
}
