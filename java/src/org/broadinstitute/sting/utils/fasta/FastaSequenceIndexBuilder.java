/*
 * Copyright (c) 2010 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.utils.fasta;

import org.broadinstitute.sting.gatk.datasources.simpleDataSources.ReferenceDataSourceProgressListener;
import org.broadinstitute.sting.utils.StingException;
import static org.broadinstitute.sting.utils.fasta.FastaSequenceIndexBuilder.Status.*;

import java.io.*;
import java.util.Iterator;

/**
 * Builds FastaSequenceIndex from fasta file.
 * Produces fai file with same output as samtools faidx
 */
public class FastaSequenceIndexBuilder {
    public File fastaFile;
    ReferenceDataSourceProgressListener progress;  // interface that provides a method for updating user on progress of reading file
    public FastaSequenceIndex sequenceIndex = new FastaSequenceIndex();

    // keep track of location in file
    long bytesRead, endOfLastLine, lastTimestamp, fileLength;  // initialized to -1 to keep 0-indexed position in file;

    // vars that store information about the contig that is currently being read
    String contig;
    long location, size, bytesPerLine, basesPerLine, basesThisLine;

    // vars that keep loop state
    byte lastByte = 0, currentByte = 0, nextByte = 0;
    public enum Status { NONE, CONTIG, FIRST_SEQ_LINE, SEQ_LINE, COMMENT }
    Status status = Status.NONE; // keeps state of what is currently being read. better to use int instead of enum?

    public FastaSequenceIndexBuilder(File fastaFile, ReferenceDataSourceProgressListener progress) {
        this.progress = progress;
        this.fastaFile = fastaFile;
        fileLength = fastaFile.length();
        parseFastaFile();
    }

    /**
     * Creates fasta sequence index from fasta file
     */
    private void parseFastaFile() {
        bytesRead = -1;
        endOfLastLine = -1;
        contig = "";
        location = 0;
        size = 0;
        bytesPerLine = 0;
        basesPerLine = 0;
        basesThisLine = 0;
        lastTimestamp = System.currentTimeMillis();

        // initialize input stream
        DataInputStream in;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(fastaFile)));
        }
        catch (Exception e) {
            throw new StingException(String.format("Could not read fasta file %s", fastaFile.getAbsolutePath()));
        }

        /*
        * iterate through each character in file one at a time, but must account for variance in line terminators
        * strategy is to check if current character is a line terminator (\n, \r), then check next character
        * only treat as end of line if next character is NOT a line terminator
        */
        try {
            // intialize iterators
            nextByte = in.readByte();
            currentByte = '\n';
            while(currentByte != -1) {

                bytesRead ++; // update position in file
                lastByte = currentByte;
                currentByte = nextByte;
                try {
                    nextByte = in.readByte();
                }                                          
                catch (EOFException e) {
                    nextByte = -1;
                }

                switch(status) {

                    // if not currently reading anything
                    // only thing that can trigger action is '>' (start of contig) or ';' (comment)
                    case NONE:
                        if (currentByte == '>')
                            status = CONTIG;
                        else if (currentByte == ';')
                            status = COMMENT;
                        break;

                    // if reading a comment, just ignore everything until end of line
                    case COMMENT:
                        if (isEol(currentByte)) {
                            if (!isEol(nextByte))
                                status = Status.NONE;
                        }
                        break;

                    // if reading a contig, add char to contig string
                    // contig string can be anything, including spaces
                    case CONTIG:
                        if (isEol(currentByte)) {
                            if (!isEol(nextByte)) {
                                status = Status.FIRST_SEQ_LINE;
                                location = bytesRead + 1;
                            }
                        }
                        else
                            contig += (char) currentByte;
                        break;

                    // record bases and bytes of first sequence line, to validate against later lines
                    case FIRST_SEQ_LINE:

                        if (isEol(currentByte)) {

                            // record bases per line if last character was a base
                            if (!isEol(lastByte)) {
                                basesPerLine = bytesRead - location;
                                basesThisLine = basesPerLine;
                                size += basesPerLine;
                            }

                            // next character is start of next line, now know bytes per line
                            if (!isEol(nextByte)) {   // figure out what to do if there is only one data line
                                bytesPerLine = bytesRead - location + 1;
                                status = Status.SEQ_LINE;
                                endOfLastLine = bytesRead;

                                // if next char is ';' or '>', then there is only one contig =>
                                if (nextByte == ';' || nextByte == '>')
                                    finishReadingContig();
                            }
                        }

                        // validate base character
                        else {
                            if (!isValidBase(currentByte))
                                throw new StingException(String.format("An invalid base was found in the contig: %s", contig));
                        }
                        break;


                    case SEQ_LINE:

                        if (isEol(currentByte)) {

                            // record bases per line if last character was a base
                            if (!isEol(lastByte)) {
                                basesThisLine = bytesRead - endOfLastLine - 1;
                                size += basesThisLine;
                            }

                            // reached end of line - check if end of contig
                            if (!isEol(nextByte)) {

                                // if comment or new contig, definitely end of sequence
                                if (nextByte == ';' || nextByte == '>')
                                    finishReadingContig();

                                    // if this line has different # of bases OR same # of bases and different # of bytes:
                                    // error if next char is a valid base, end of contig otherwise
                                else if (basesThisLine != basesPerLine || bytesPerLine != bytesRead - endOfLastLine) {
                                    if (isValidBase(nextByte) && nextByte != -1) {
                                        throw new StingException(String.format("An invalid line was found in the contig: %s", contig));
                                    }
                                    else
                                        finishReadingContig();
                                }
                                endOfLastLine = bytesRead;
                            }
                        }

                        // validate base character
                        else {
                            if (!isValidBase(currentByte))
                                throw new StingException(String.format("An invalid base was found in the contig: %s", contig));
                        }
                        break;
                }
            }
        }
        catch (IOException e) {
            throw new StingException(String.format("Could not read fasta file %s", fastaFile.getAbsolutePath()), e);        }
        catch (Exception e) {
            throw new StingException(e.getMessage(), e);
        }
    }

    /**
     * Checks if character is an end of line character, \n or \r
     * @param currentByte Character to check, as a byte
     * @return True if current character is \n or \r' false otherwise
     */
    private boolean isEol(byte currentByte) {
        return (currentByte == '\n' || currentByte == '\r');
    }


    /**
     * checks that character is valid base
     * only checks that the base isn't whitespace, like picard does
     * could add check that character is A/C/G/T/U if wanted
     * @param currentByte Character to check, as a byte
     * @return True if character is not whitespace, false otherwise
     */
    private boolean isValidBase(byte currentByte) {
        return (!Character.isWhitespace(currentByte) && currentByte != ';' && currentByte != '>');
    }

    /*
     * When reader reaches the end of a contig
     * Reset iterators and add contig to sequence index
     */
    private void finishReadingContig() {
        sequenceIndex.addIndexEntry(contig, location, size, (int) basesPerLine, (int) bytesPerLine);
        status = Status.NONE;
        contig = "";
        size = 0;

        if (System.currentTimeMillis() - lastTimestamp > 10000) {
            int percentProgress = (int) (100*bytesRead/fileLength);
            if (progress != null)
                progress.percentProgress(percentProgress);
            lastTimestamp = System.currentTimeMillis();
        }
    }

    /**
     * Stores FastaSequenceIndex as a .fasta.fai file on local machine
     * Although method is public it cannot be called on any old FastaSequenceIndex - must be created by a FastaSequenceIndexBuilder
     */
    public void saveAsFaiFile() {
        File indexFile  = new File(fastaFile.getAbsolutePath() + ".fai");

        if (indexFile.exists()) {
            throw new StingException(String.format("Fai file could not be created, because a file with name %s already exists." +
                    "Please delete or rename this file and try again.", indexFile.getAbsolutePath()));
        }

        BufferedWriter out;
        try {
            out = new BufferedWriter(new FileWriter(indexFile));
        }
        catch (Exception e) {
            throw new StingException(String.format("Could not open file %s for writing. Check that GATK is permitted to write to disk.",
                    indexFile.getAbsolutePath()), e);   
        }

        Iterator<FastaSequenceIndexEntry> iter = sequenceIndex.iterator();

        try {
            while (iter.hasNext()) {
                out.write(iter.next().toIndexFileLine());
                out.newLine();
            }
            out.close();
        }
        catch (Exception e) {
            throw new StingException(String.format("An error occurred while writing file %s", e));
        }
    }
}