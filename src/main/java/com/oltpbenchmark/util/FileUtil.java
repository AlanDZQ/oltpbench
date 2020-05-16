/******************************************************************************
 *  Copyright 2015 by OLTPBenchmark Project                                   *
 *                                                                            *
 *  Licensed under the Apache License, Version 2.0 (the "License");           *
 *  you may not use this file except in compliance with the License.          *
 *  You may obtain a copy of the License at                                   *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 *  Unless required by applicable law or agreed to in writing, software       *
 *  distributed under the License is distributed on an "AS IS" BASIS,         *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 *  See the License for the specific language governing permissions and       *
 *  limitations under the License.                                            *
 ******************************************************************************/


package com.oltpbenchmark.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * @author pavlo
 */
public abstract class FileUtil {
    private static final Logger LOG = LoggerFactory.getLogger(FileUtil.class);

    private static final Pattern EXT_SPLIT = Pattern.compile("\\.");


    /**
     * Join path components
     *
     * @param args
     * @return
     */
    public static String joinPath(String... args) {
        StringBuilder result = new StringBuilder();
        boolean first = true;
        for (String a : args) {
            if (a != null && a.length() > 0) {
                if (!first) {
                    result.append("/");
                }
                result.append(a);
                first = false;
            }
        }
        return result.toString();
    }

    /**
     * Given a basename for a file, find the next possible filename if this file
     * already exists. For example, if the file test.res already exists, create
     * a file called, test.1.res
     *
     * @param basename
     * @return
     */
    public static String getNextFilename(String basename) {

        if (!exists(basename)) {
            return basename;
        }

        File f = new File(basename);
        if (f != null && f.isFile()) {
            String[] parts = EXT_SPLIT.split(basename);

            // Check how many files already exist
            int counter = 1;
            String nextName = parts[0] + "." + counter + "." + parts[1];
            while (exists(nextName)) {
                ++counter;
                nextName = parts[0] + "." + counter + "." + parts[1];
            }
            return nextName;
        }


        // Should we throw instead??
        return null;
    }

    public static boolean exists(String path) {
        return (new File(path).exists());
    }

    /**
     * Create any directory in the list paths if it doesn't exist
     *
     * @param paths
     */
    public static void makeDirIfNotExists(String... paths) {
        for (String p : paths) {
            if (p == null) {
                continue;
            }
            File f = new File(p);
            if (f.exists() == false) {
                f.mkdirs();
            }
        } // FOR
    }

    public static String readFile(String path) {
        StringBuilder buffer = new StringBuilder();
        try (BufferedReader in = FileUtil.getReader(path)) {
            while (in.ready()) {
                buffer.append(in.readLine()).append("\n");
            } // WHILE
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read file contents from '" + path + "'", ex);
        }
        return (buffer.toString());
    }

    /**
     * Creates a BufferedReader for the given input path Can handle both gzip
     * and plain text files
     *
     * @param path
     * @return
     * @throws IOException
     */
    public static BufferedReader getReader(String path) throws IOException {
        return (FileUtil.getReader(new File(path)));
    }

    /**
     * Creates a BufferedReader for the given input path Can handle both gzip
     * and plain text files
     *
     * @param file
     * @return
     * @throws IOException
     */
    public static BufferedReader getReader(File file) throws IOException {
        if (!file.exists()) {
            throw new IOException("The file '" + file + "' does not exist");
        }

        BufferedReader in = new BufferedReader(new FileReader(file));
        LOG.debug("Reading in the contents of '{}'", file.getName());

        return (in);
    }

    /**
     * Find the path to a directory below our current location in the source
     * tree Throws a RuntimeException if we go beyond our repository checkout
     *
     * @param dirName
     * @return
     * @throws IOException
     */
    public static File findDirectory(String dirName) throws IOException {
        return (FileUtil.find(dirName, new File(".").getCanonicalFile(), true).getCanonicalFile());
    }

    private static final File find(String name, File current, boolean isdir) throws IOException {
        LOG.debug("Find Current Location = {}", current);
        boolean has_svn = false;
        for (File file : current.listFiles()) {
            if (file.getCanonicalPath().endsWith(File.separator + name) && file.isDirectory() == isdir) {
                return (file);
                // Make sure that we don't go to far down...
            } else if (file.getCanonicalPath().endsWith(File.separator + ".svn")) {
                has_svn = true;
            }
        } // FOR
        // If we didn't see an .svn directory, then we went too far down
        if (!has_svn) {
            throw new RuntimeException("Unable to find directory '" + name + "' [last_dir=" + current.getAbsolutePath() + "]");
        }
        File next = new File(current.getCanonicalPath() + File.separator + "..");
        return (FileUtil.find(name, next, isdir));
    }

}
